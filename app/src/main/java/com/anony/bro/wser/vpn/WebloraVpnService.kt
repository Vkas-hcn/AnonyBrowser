package com.anony.bro.wser.vpn

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.net.Uri
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anony.bro.wser.R
import com.anony.bro.wser.data.LoadingTracking
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.hellohello.NoticeConfigStore
import com.anony.bro.wser.view.guide.GuideActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VPN 常驻通知栏服务。
 *
 * 使用与新闻通知相同的自定义 RemoteViews 样式（notification_vpn_col / cos / coy）：
 *  - VPN 已连接：显示「Private Network - Protection Status」
 *  - VPN 未连接：显示「Public Network - Unprotected」
 *
 * 保活/拉活参考 UltraClean 常驻栏做法：START_STICKY + 开机/前台/任务移除拉起。
 */
@SuppressLint("VpnServicePolicy")
class AnonyBrowserVpnService : VpnService() {

    companion object {
        private const val TAG = "BoxVpnService"

        const val NOTIFICATION_ID = 9801

        private const val CHANNEL_ID = "vpn_persistent_bar"
        const val ACTION_SHOW_BAR = "com.anony.bro.wser.vpn.action.SHOW_BAR"
        const val ACTION_REFRESH_NEWS_CONFIG = "com.anony.bro.wser.vpn.action.REFRESH_NEWS_CONFIG"

        /** 已连接状态文案。 */
        /** 断开状态文案。 */
        @Volatile
        private var barAlerted = false
    }

    private var engine: LibboxEngine? = null
    private val handler = Handler(Looper.getMainLooper())
    private var speedRunnable: Runnable? = null
    private val newsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var newsRefreshJob: Job? = null
    @Volatile private var currentNews: VpnNewsContent? = null

    private var baseTxBytes = 0L
    private var baseRxBytes = 0L
    private var lastTotalUp = 0L
    private var lastTotalDown = 0L

    private val stateCallback = object : VpnCallback {
        override fun onStateChanged(state: VpnState) {
            refreshBar()
        }
    }

    fun createVpnBuilder(): Builder = Builder()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "lifecycle onCreate: pid=${Process.myPid()}, vpnRequested=${VpnManager.isRunRequested()}")
        engine = LibboxEngine(this)
        VpnManager.onServiceCreated(this)
        VpnManager.addCallback(stateCallback)
        createNotificationChannel()
        runCatching { startForegroundCompat(buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground on create failed", it) }
        restartNewsRefresh()
        alertOnFirstShow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(
            TAG,
            "lifecycle onStartCommand: pid=${Process.myPid()}, action=${intent?.action}, flags=$flags, startId=$startId, vpnRequested=${VpnManager.isRunRequested()}",
        )
        runCatching { startForegroundCompat(buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground on start failed", it) }
        alertOnFirstShow()

        if (intent?.action == ACTION_REFRESH_NEWS_CONFIG) {
            restartNewsRefresh()
            return START_STICKY
        }

        if (intent?.action == ACTION_SHOW_BAR) {
            refreshBar()
            return START_STICKY
        }

        if (!VpnManager.isRunRequested()) {
            refreshBar()
            return START_STICKY
        }

        VpnManager.updateState(VpnState.CONNECTING)
        refreshBar()

        try {
            val config = VpnManager.getConfig()
            if (config.isBlank()) {
                VpnManager.notifyError(getString(R.string.vpn_config_empty))
                VpnManager.updateState(VpnState.DISCONNECTED)
                refreshBar()
                return START_STICKY
            }

            if (engine == null) engine = LibboxEngine(this)
            engine?.start(config)

            val uid = Process.myUid()
            baseTxBytes = TrafficStats.getUidTxBytes(uid)
            baseRxBytes = TrafficStats.getUidRxBytes(uid)
            lastTotalUp = 0L
            lastTotalDown = 0L

            VpnManager.updateState(VpnState.CONNECTED)
            startSpeedTimer()
            refreshBar()

            Log.d(TAG, "VPN connected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            VpnManager.notifyError(getString(R.string.vpn_start_failed_format, e.message.orEmpty()))
            VpnManager.updateState(VpnState.DISCONNECTED)
            refreshBar()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "lifecycle onTaskRemoved: pid=${Process.myPid()}; scheduling service restart")
        runCatching {
            val restartIntent = Intent(applicationContext, AnonyBrowserVpnService::class.java)
                .setAction(ACTION_SHOW_BAR)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getForegroundService(this, 1, restartIntent, flags)
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 1500L, pendingIntent)
        }.onFailure { Log.w(TAG, "schedule restart failed", it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "lifecycle onDestroy: pid=${Process.myPid()}, vpnState=${VpnManager.state}")
        stopSpeedTimer()
        stopNewsRefresh()
        engine?.stop()
        engine = null
        VpnManager.removeCallback(stateCallback)
        VpnManager.onServiceDestroyed()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "lifecycle onRevoke: pid=${Process.myPid()}")
        disconnect()
    }

    /** 断开隧道，保留常驻栏，文案切换为未保护。 */
    fun disconnect() {
        VpnManager.updateState(VpnState.DISCONNECTING)
        stopSpeedTimer()
        engine?.stop()
        engine = LibboxEngine(this)
        VpnManager.updateState(VpnState.DISCONNECTED)
        refreshBar()
    }

    private fun startSpeedTimer() {
        stopSpeedTimer()
        speedRunnable = object : Runnable {
            override fun run() {
                updateTraffic()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(speedRunnable!!)
    }

    private fun stopSpeedTimer() {
        speedRunnable?.let { handler.removeCallbacks(it) }
        speedRunnable = null
    }

    /** 仅更新流量统计，不再刷新通知（通知只展示状态文案）。 */
    private fun updateTraffic() {
        val uid = Process.myUid()
        val curTx = TrafficStats.getUidTxBytes(uid)
        val curRx = TrafficStats.getUidRxBytes(uid)
        if (curTx == TrafficStats.UNSUPPORTED.toLong()) return

        val totalUp = curTx - baseTxBytes
        val totalDown = curRx - baseRxBytes
        val speedUp = totalUp - lastTotalUp
        val speedDown = totalDown - lastTotalDown
        lastTotalUp = totalUp
        lastTotalDown = totalDown

        VpnManager.updateTraffic(speedUp, speedDown, totalUp, totalDown)
    }

    // --- 自定义样式通知：左图标 + 上标题 + 下状态 ---

    private fun statusText(): String =
        if (VpnManager.state == VpnState.CONNECTED) {
            getString(R.string.vpn_bar_protected)
        } else {
            getString(R.string.vpn_bar_unprotected)
        }

    private fun buildNotification(): Notification {
        val title = currentNews?.title ?: getString(R.string.app_name)
        val status = statusText()
        val contentIntent = createContentIntent(currentNews?.url)
        val contentView = createBarView(title, status)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setDeleteIntent(createDismissIntent())
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setCustomHeadsUpContentView(contentView)
            .build()
    }

    private fun createBarView(title: String, status: String): RemoteViews =
        RemoteViews(packageName, R.layout.notification_vpn_bar).apply {
            setTextViewText(R.id.vpn_bar_title, title)
            setTextViewText(R.id.vpn_bar_status, status)
        }

    private fun createContentIntent(url: String?): PendingIntent {
        val intent = GuideActivity.createHotStartIntent(this).apply {
            putExtra(LoadingTracking.EXTRA_FROM_SYSTEM, true)
            if (!url.isNullOrBlank()) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
            }
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createDismissIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            NOTIFICATION_ID,
            Intent(this, VpnBarDismissReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun refreshBar() {
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { Log.w(TAG, "refreshBar failed", it) }
    }

    private fun restartNewsRefresh() {
        stopNewsRefresh()
        val intervalMs = NoticeConfigStore.getVpnReminderConfig().newsRefreshMinutes * 60_000L
        newsRefreshJob = newsScope.launch {
            refreshNews()
            while (isActive) {
                delay(intervalMs)
                refreshNews()
            }
        }
    }

    private fun stopNewsRefresh() {
        newsRefreshJob?.cancel()
        newsRefreshJob = null
    }

    private suspend fun refreshNews() {
        VpnNewsContentRepository.nextContent(this@AnonyBrowserVpnService)?.let {
            currentNews = it
            refreshBar()
        }
    }

    private fun canPostNotifications(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName(getString(R.string.vpn_status_channel))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun alertOnFirstShow() {
        if (barAlerted || !canPostNotifications()) return
        barAlerted = true
        UpDataTool.trackEvent("news_notification_sent")
        runCatching { vibrateOnce() }.onFailure { Log.w(TAG, "vibrate failed", it) }
        runCatching { playAlertSound() }.onFailure { Log.w(TAG, "sound failed", it) }
    }

    private fun vibrateOnce() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(400L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun playAlertSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        RingtoneManager.getRingtone(applicationContext, uri)?.play()
    }
}
