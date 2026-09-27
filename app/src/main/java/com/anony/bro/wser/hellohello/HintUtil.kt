package com.anony.bro.wser.hellohello

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anony.bro.wser.R
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.recommendations.WebsiteCategory
import com.anony.bro.wser.view.guide.GuideActivity
import com.anony.bro.wser.vpn.VpnBarLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object HintUtil {
    private const val TAG = "NewsNotification"
    const val CO_SHOW_TYPE = "nUBI3nLEBU"
    const val CO_NOTIFICATION_ID = "news_notification_id"
    const val CO_S_TYPE_UNLOCK = 0
    const val CO_S_TYPE_INTERVAL = 1
    const val CO_S_TYPE_ALARM = 2

    private const val NEWS_CHANNEL_ID = "browser_news_updates_v2"
    private const val NEWS_SILENT_CHANNEL_ID = "browser_news_updates_silent_v2"
    private const val NOTIFICATION_ID_PREFS = "news_notification_ids"
    private const val KEY_LAST_NOTIFICATION_ID = "last_notification_id"
    private const val FIRST_DYNAMIC_NOTIFICATION_ID = 70_000
    private const val CO_SP_UNLOCK_N = "ihenb83bne"
    private const val CO_SP_UNLOCK_T = "nUen4noIEBI"
    private const val CO_SP_INTERAL_N = "Un3oib(UH3f"
    private const val CO_SP_INTERAL_T = "PnowB93B(ILK"
    private const val CO_SP_ALARM_N = "d#nbefOINEFLN"
    private const val CO_SP_ALARM_T = "ONebo0#kkjfwI"
    private const val CO_SP_ALARM_NEXT_TIME = "IienOeniorgUen"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val bannerSessions = BannerNotificationSessionManager()
    private val notificationIdLock = Any()
    private var initialized = false
    private var isForeground = false
    private var activityCount = 0

    fun coHavePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                coIntance(),
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(coIntance()).areNotificationsEnabled()
        }

    fun init(application: Application) {
        if (initialized) {
            Log.d(TAG, "init skipped: already initialized")
            return
        }
        initialized = true
        Log.i(TAG, "init: manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}")
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activityCount++ == 0) {
                    isForeground = true
                    Log.d(TAG, "app entered foreground: ${activity.javaClass.simpleName}")
                }
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount = max(0, activityCount - 1)
                if (activityCount == 0) {
                    isForeground = false
                    Log.d(TAG, "app entered background: ${activity.javaClass.simpleName}")
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        scope.launch {
            delay(2_627)
            while (true) {
                Log.d(TAG, "interval trigger received")
                sendTone()
                delay(59_987)
            }
        }

        MessageRecv.subscribeTopic(source = "启动")
        ContextCompat.registerReceiver(
            application,
            PhoneLockRe(),
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "ACTION_USER_PRESENT receiver registered")
        ContextCompat.registerReceiver(
            application,
            HomeRecentsReceiver(),
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "CLOSE_SYSTEM_DIALOGS receiver registered")
        markLing()
    }

    fun coIntance(): Context = GateBrowserApplication.get()

    fun startJob(context: Context) {
        AssistanceLaunceJob.enqueueWork(context)
    }

    fun launchFg(coShowOneNt: Boolean = false) {
        VpnBarLauncher.ensureRunning(coIntance())
    }

    private suspend fun sendTone() {
        sendLatestNews(CO_S_TYPE_INTERVAL)
    }

    fun sendTtwo(onComplete: () -> Unit = {}) =
        sendLatestNewsAsync(CO_S_TYPE_UNLOCK, onComplete)

    fun sendTthree(onComplete: () -> Unit = {}) {
        sendLatestNewsAsync(CO_S_TYPE_ALARM, onComplete)
    }

    fun cancelNewsNotification(showType: Int, notificationId: Int? = null) {
        val resolvedNotificationId = notificationId ?: legacyNotificationId(showType)
        bannerSessions.snapshot()
            ?.takeIf { it.notificationId == resolvedNotificationId }
            ?.let { bannerSessions.cancel(it.sessionId) }
        NotificationManagerCompat.from(coIntance()).cancel(resolvedNotificationId)
        NotificationContentRepository.resetCopyCache()
        Log.d(TAG, "notification cancelled: type=${showTypeName(showType)}, id=$resolvedNotificationId")
    }

    fun restorePersistentBannerIfActive(notificationId: Int, showType: Int) {
        val session = bannerSessions.snapshot()?.takeIf { it.notificationId == notificationId } ?: return
        if (isDeviceLocked() || !canPostNewsNotifications()) return
        sendStandardNotification(
            session.content,
            showType,
            notificationId,
            alert = refreshAlert(silent = !NoticeConfigStore.bannerNotifyConfig().ringEnabled),
            sticky = true,
            trackSent = false,
        )
        Log.d(TAG, "persistent banner restored after dismiss: id=$notificationId")
    }

    fun markLing() {
        scope.launch {
            if (NoticeConfigStore.isKoreanCountry() && NoticeConfigStore.isSamsung()) {
                Log.d(TAG, "alarm scheduling skipped: Korean Samsung policy")
                return@launch
            }
            val config = NoticeConfigStore.getReceiveConfig()
            if (config.coaTime <= 0) {
                Log.d(TAG, "alarm scheduling skipped: alarm interval=${config.coaTime}")
                return@launch
            }

            val now = System.currentTimeMillis()
            val nextAlarmAt = NoticeConfigStore.getLong(CO_SP_ALARM_NEXT_TIME)
            if (nextAlarmAt > now) {
                Log.d(TAG, "alarm scheduling skipped: existing alarm at=$nextAlarmAt, now=$now")
                return@launch
            }

            val setTime = now + config.coaTime * 60_000L
            val alarm = coIntance().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(coIntance(), TimeWorkerRe::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                coIntance(),
                randomRequestCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            NoticeConfigStore.putLong(CO_SP_ALARM_NEXT_TIME, setTime)
            AlarmManagerCompat.setAndAllowWhileIdle(alarm, AlarmManager.RTC_WAKEUP, setTime, pendingIntent)
            Log.i(TAG, "alarm scheduled: triggerAt=$setTime, intervalMinutes=${config.coaTime}")
        }
    }

    private suspend fun canSend(showType: Int): Boolean {
        val type = showTypeName(showType)
        val notificationManager = NotificationManagerCompat.from(coIntance())
        val permissionGranted = coHavePermission()
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        if (!permissionGranted || !notificationsEnabled) {
            Log.w(TAG, "send blocked [$type]: permissionGranted=$permissionGranted, notificationsEnabled=$notificationsEnabled")
            return false
        }
        val config = NoticeConfigStore.getReceiveConfig()
        if (!config.coIsSwitch) {
            Log.d(TAG, "send blocked [$type]: notification switch is off")
            return false
        }
        if (NoticeConfigStore.isKoreanCountry() && NoticeConfigStore.isSamsung()) {
            Log.d(TAG, "send blocked [$type]: Korean Samsung policy")
            return false
        }
        if (isForeground) {
            Log.d(TAG, "send blocked [$type]: app is foreground")
            return false
        }

        val lastTimeKey = lastTimeKey(showType)
        val countKey = countKey(showType)
        val lastTime = NoticeConfigStore.getLong(lastTimeKey)
        if (!lastTime.isToday()) NoticeConfigStore.putLong(countKey, 0)

        val sent = NoticeConfigStore.getLong(countKey)
        val limit = when (showType) {
            CO_S_TYPE_UNLOCK -> config.couCount
            CO_S_TYPE_INTERVAL -> config.cotCount
            else -> config.coaCount
        }
        val cooldownMinutes = when (showType) {
            CO_S_TYPE_UNLOCK -> config.couTime
            CO_S_TYPE_INTERVAL -> config.cotTime
            else -> config.coaTime
        }
        val now = System.currentTimeMillis()
        if (cooldownMinutes * 60_000L > now - lastTime) {
            Log.d(TAG, "send blocked [$type]: cooldownMinutes=$cooldownMinutes, lastSent=$lastTime, now=$now")
            return false
        }
        if (sent >= limit) {
            Log.d(TAG, "send blocked [$type]: dailySent=$sent, dailyLimit=$limit")
            return false
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = min(config.freeTimeOne, config.freeTimeTwo)
        val end = max(config.freeTimeOne, config.freeTimeTwo)
        val allowed = hour in start..end
        Log.d(TAG, "send eligibility [$type]: allowed=$allowed, hour=$hour, allowedHours=$start..$end, dailySent=$sent/$limit")
        return allowed
    }

    private fun recordSent(showType: Int) {
        val countKey = countKey(showType)
        NoticeConfigStore.putLong(lastTimeKey(showType), System.currentTimeMillis())
        NoticeConfigStore.putLong(countKey, NoticeConfigStore.getLong(countKey) + 1)
        Log.i(TAG, "send recorded [${showTypeName(showType)}]: dailySent=${NoticeConfigStore.getLong(countKey)}")
    }

    private fun sendLatestNewsAsync(showType: Int, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                Log.d(TAG, "async send started [${showTypeName(showType)}]")
                sendLatestNews(showType)
            } catch (error: Throwable) {
                Log.e(TAG, "async send failed [${showTypeName(showType)}]", error)
            } finally {
                Log.d(TAG, "async send finished [${showTypeName(showType)}]")
                onComplete()
            }
        }
    }

    private suspend fun sendLatestNews(showType: Int) {
        val type = showTypeName(showType)
        Log.i(TAG, "send started [$type]")
        if (bannerSessions.isActive()) {
            Log.d(TAG, "send blocked [$type]: persistent banner is active")
            return
        }
        if (!canSend(showType)) return
        val bannerConfig = NoticeConfigStore.bannerNotifyConfig()
        val content = NotificationContentRepository.randomContent(coIntance())
        if (content == null) {
            Log.w(TAG, "send blocked [$type]: no notification content")
            return
        }
        Log.d(
            TAG,
            "recommendation ready [$type]: category=${content.category.name}, titleLength=${content.title.length}, summaryLength=${content.summary.length}",
        )
        if (!canSend(showType)) {
            Log.d(TAG, "send aborted [$type]: eligibility changed after content fetch")
            return
        }
        val notificationId = nextNotificationId()
        if (bannerConfig.enabled && bannerConfig.durationSeconds > 0 && !isDeviceLocked()) {
            sendPersistentNotification(content, showType, notificationId, bannerConfig.durationSeconds, bannerConfig.ringEnabled)
        } else {
            if (bannerConfig.enabled && bannerConfig.durationSeconds > 0) {
                Log.d(TAG, "persistent banner skipped [$type]: device is locked")
            }
            sendStandardNotification(content, showType, notificationId)
            recordSent(showType)
        }
    }

    private suspend fun sendPersistentNotification(
        content: NotificationContent,
        showType: Int,
        notificationId: Int,
        durationSeconds: Int,
        ringEnabled: Boolean,
    ) {
        val session = bannerSessions.tryStart(content, durationSeconds, notificationId) ?: run {
            Log.d(TAG, "persistent banner skipped: another session became active")
            return
        }
        Log.i(
            TAG,
            "persistent banner started: newsId=${session.newsId}, triggers=${session.remainingTriggers}, expiresAt=${session.expiresAtElapsedMs}",
        )
        var firstNotification = true
        try {
            while (true) {
                val dispatch = bannerSessions.markShown(session.sessionId) ?: break
                sendStandardNotification(
                    content,
                    showType,
                    session.notificationId,
                    alert = if (dispatch.isFirstInSession) {
                        firstShowAlert
                    } else {
                        refreshAlert(silent = !ringEnabled)
                    },
                    sticky = true,
                    trackSent = firstNotification,
                )
                if (firstNotification) {
                    recordSent(showType)
                    firstNotification = false
                }
                delay(BannerNotificationSessionManager.STAY_MS)
                val shouldRepeat = bannerSessions.finishStay(session.sessionId)
                if (!shouldRepeat) {
                    break
                }
                if (isDeviceLocked()) {
                    Log.d(TAG, "persistent banner stopped: device locked")
                    break
                }
                if (!canPostNewsNotifications()) {
                    Log.w(TAG, "persistent banner stopped: notification permission or switch changed")
                    break
                }
            }
        } finally {
            val ended = bannerSessions.cancel(session.sessionId)
            if (ended != null) {
                sendStandardNotification(
                    content,
                    showType,
                    session.notificationId,
                    alert = refreshAlert(silent = true),
                    sticky = false,
                    trackSent = false,
                )
            }
            Log.d(TAG, "persistent banner finished: newsId=${content.id}")
        }
    }

    private fun canPostNewsNotifications(): Boolean =
        coHavePermission() && NotificationManagerCompat.from(coIntance()).areNotificationsEnabled()

    private fun isDeviceLocked(): Boolean =
        coIntance().getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun sendStandardNotification(
        content: NotificationContent,
        showType: Int,
        notificationId: Int,
        alert: AlertProfile = firstShowAlert,
        sticky: Boolean = false,
        trackSent: Boolean = true,
    ) {
        val notification = createOnceNt(content, showType, notificationId, alert, sticky)
        getManager().notify(notificationId, notification)
        val channelId = if (alert.silent) NEWS_SILENT_CHANNEL_ID else NEWS_CHANNEL_ID
        Log.i(
            TAG,
            "notify submitted [${showTypeName(showType)}]: category=${content.category.name}, id=$notificationId, channel=$channelId, sticky=$sticky",
        )
        if (trackSent) {
            UpDataTool.trackEvent("news_notification_sent")
        }
    }

    private fun createOnceNt(
        content: NotificationContent,
        showType: Int,
        notificationId: Int,
        alert: AlertProfile,
        sticky: Boolean,
    ): Notification {
        val channelId = if (alert.silent) NEWS_SILENT_CHANNEL_ID else NEWS_CHANNEL_ID
        createChannel(
            channelId,
            R.string.notification_channel_news,
            NotificationManagerCompat.IMPORTANCE_HIGH,
            silent = alert.silent,
        )
        val contentIntent = createIntent(content.url, showType, notificationId)
        return NotificationCompat.Builder(coIntance(), channelId)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(alert.onlyAlertOnce)
            .setSilent(alert.silent)
            .applyAlertBehavior(alert)
            .setContentIntent(contentIntent)
            .apply {
                if (sticky) {
                    setOngoing(true)
                    setDeleteIntent(createDismissIntent(showType, notificationId))
                }
            }
            .setCustomContentView(createCollapsedNewsView(content, contentIntent))
            .setCustomBigContentView(createLargeNewsView(content, contentIntent))
            .setCustomHeadsUpContentView(createCollapsedNewsView(content, contentIntent))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()
    }

    private fun NotificationCompat.Builder.applyAlertBehavior(alert: AlertProfile): NotificationCompat.Builder {
        if (alert.silent) {
            return setDefaults(0).setVibrate(null)
        }
        if (alert.vibrate) {
            return setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setVibrate(longArrayOf(0, 300, 200, 300))
        }
        return setDefaults(NotificationCompat.DEFAULT_SOUND).setVibrate(null)
    }

    private data class AlertProfile(
        val onlyAlertOnce: Boolean,
        val silent: Boolean,
        val vibrate: Boolean,
    )

    private val firstShowAlert = AlertProfile(
        onlyAlertOnce = false,
        silent = false,
        vibrate = true,
    )

    private fun refreshAlert(silent: Boolean) = AlertProfile(
        onlyAlertOnce = silent,
        silent = silent,
        vibrate = false,
    )

    private fun createCollapsedNewsView(
        content: NotificationContent,
        contentIntent: PendingIntent,
    ): RemoteViews =
        RemoteViews(
            coIntance().packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                R.layout.notification_cos
            } else {
                R.layout.notification_col
            },
        ).also {
            bindCollapsedNewsView(it, content, contentIntent)
        }

    private fun createLargeNewsView(
        content: NotificationContent,
        contentIntent: PendingIntent,
    ): RemoteViews =
        RemoteViews(coIntance().packageName, R.layout.notification_coy).also {
            bindExpandedNewsView(it, content, contentIntent)
        }

    private fun bindCollapsedNewsView(
        view: RemoteViews,
        content: NotificationContent,
        contentIntent: PendingIntent,
    ) {
        val textColor = ContextCompat.getColor(coIntance(), R.color.notification_news_text)
        view.setTextViewText(R.id.news_title, content.title)
        view.setTextViewText(R.id.news_action, content.action)
        view.setTextColor(R.id.news_title, textColor)
        view.setInt(R.id.news_action, "setBackgroundResource", actionBackground(content.category, expanded = false))
        view.setOnClickPendingIntent(R.id.news_action, contentIntent)
    }

    private fun bindExpandedNewsView(
        view: RemoteViews,
        content: NotificationContent,
        contentIntent: PendingIntent,
    ) {
        val textColor = ContextCompat.getColor(coIntance(), R.color.notification_news_text)
        view.setTextViewText(R.id.news_title, content.title)
        view.setTextViewText(R.id.news_caption, content.summary)
        view.setTextViewText(R.id.news_action, content.action)
        view.setTextColor(R.id.news_title, textColor)
        view.setTextColor(R.id.news_caption, textColor)
        view.setImageViewResource(R.id.news_artwork, artwork(content.category))
        view.setInt(R.id.news_action, "setBackgroundResource", actionBackground(content.category, expanded = true))
        view.setOnClickPendingIntent(R.id.news_action, contentIntent)
    }

    private fun artwork(category: WebsiteCategory): Int = when (category) {
        WebsiteCategory.NEWS -> R.drawable.ic_notify_news
        WebsiteCategory.WEATHER -> R.drawable.ic_notify_weather
        WebsiteCategory.EXCHANGE -> R.drawable.ic_notify_rate
        WebsiteCategory.CURIOSITY -> R.drawable.ic_notify_fun
    }

    private fun actionBackground(category: WebsiteCategory, expanded: Boolean): Int = when (category) {
        WebsiteCategory.NEWS -> if (expanded) R.drawable.bg_click_news_long else R.drawable.bg_click_news_short
        WebsiteCategory.WEATHER -> if (expanded) R.drawable.bg_click_weather_long else R.drawable.bg_click_weather_short
        WebsiteCategory.EXCHANGE -> if (expanded) R.drawable.bg_click_rate_long else R.drawable.bg_click_rate_short
        WebsiteCategory.CURIOSITY -> if (expanded) R.drawable.bg_click_fun_long else R.drawable.bg_click_fun_short
    }

    private fun createIntent(url: String, showType: Int, notificationId: Int): PendingIntent {
        val intent = GuideActivity.createHotStartIntent(coIntance()).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(url)
            putExtra(CO_SHOW_TYPE, showType)
            putExtra(CO_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getActivity(
            coIntance(),
            randomRequestCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createDismissIntent(showType: Int, notificationId: Int): PendingIntent {
        val intent = Intent(coIntance(), NewsBannerDismissReceiver::class.java).apply {
            putExtra(CO_SHOW_TYPE, showType)
            putExtra(CO_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            coIntance(),
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel(
        id: String,
        nameRes: Int,
        importance: Int,
        silent: Boolean,
    ) {
        NotificationManagerCompat.from(coIntance()).createNotificationChannel(
            NotificationChannelCompat.Builder(id, importance)
                .setName(coIntance().getString(nameRes))
                .setSound(if (silent) null else android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                .setLightsEnabled(!silent)
                .setVibrationEnabled(!silent)
                .setShowBadge(!silent)
                .build(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = getManager().getNotificationChannel(id)
            Log.d(TAG, "channel ready: id=$id, importance=${channel?.importance}, sound=${channel?.sound}")
        }
    }

    private fun getManager(): NotificationManager =
        coIntance().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun lastTimeKey(showType: Int): String = when (showType) {
        CO_S_TYPE_UNLOCK -> CO_SP_UNLOCK_T
        CO_S_TYPE_INTERVAL -> CO_SP_INTERAL_T
        else -> CO_SP_ALARM_T
    }

    private fun countKey(showType: Int): String = when (showType) {
        CO_S_TYPE_UNLOCK -> CO_SP_UNLOCK_N
        CO_S_TYPE_INTERVAL -> CO_SP_INTERAL_N
        else -> CO_SP_ALARM_N
    }

    private fun nextNotificationId(): Int = synchronized(notificationIdLock) {
        val prefs = coIntance().getSharedPreferences(NOTIFICATION_ID_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(KEY_LAST_NOTIFICATION_ID, FIRST_DYNAMIC_NOTIFICATION_ID - 1)
        val next = if (last in FIRST_DYNAMIC_NOTIFICATION_ID until Int.MAX_VALUE) last + 1 else FIRST_DYNAMIC_NOTIFICATION_ID
        prefs.edit().putInt(KEY_LAST_NOTIFICATION_ID, next).commit()
        next
    }

    private fun legacyNotificationId(showType: Int): Int = when (showType) {
        CO_S_TYPE_UNLOCK -> 60111
        CO_S_TYPE_INTERVAL -> 60101
        else -> 60121
    }

    private fun showTypeName(showType: Int): String = when (showType) {
        CO_S_TYPE_UNLOCK -> "unlock"
        CO_S_TYPE_INTERVAL -> "interval"
        CO_S_TYPE_ALARM -> "alarm"
        else -> "unknown($showType)"
    }

    private fun Long.isToday(): Boolean {
        if (this <= 0) return false
        val calendar = Calendar.getInstance()
        val today = LocalDate.of(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        calendar.timeInMillis = this
        return today == LocalDate.of(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun randomRequestCode(): Int = Random.nextInt(1, Int.MAX_VALUE)

}
