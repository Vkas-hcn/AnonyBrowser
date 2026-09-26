package com.anony.bro.wser.hellohello

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.anony.bro.wser.R
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.view.guide.GuideActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object VpnReminderNotifier {
    private const val CHANNEL_ID = "vpn_protection_reminders"
    private const val NOTIFICATION_ID = 70_801
    const val EXTRA_OPENED_FROM_VPN_REMINDER = "extra_opened_from_vpn_reminder"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reminderJob: Job? = null
    private lateinit var context: Context

    fun init(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                cancelReminderTimer()
            }

            override fun onStop(owner: LifecycleOwner) {
                scheduleForCurrentBackground()
            }
        })
    }

    fun onConfigChanged() {
        if (!::context.isInitialized) return
        if (GateBrowserApplication.get().isAppForeground()) {
            cancelReminderTimer()
        } else {
            scheduleForCurrentBackground()
        }
    }

    fun dismissIfOpenedFromReminder(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPENED_FROM_VPN_REMINDER, false)) {
            cancelNotification()
        }
    }

    private fun scheduleForCurrentBackground() {
        cancelReminderTimer()
        val config = NoticeConfigStore.getVpnReminderConfig()
        if (!config.enabled || config.delayMinutes <= 0) {
            cancelNotification()
            return
        }

        val intervalMs = config.delayMinutes * 60_000L
        reminderJob = scope.launch {
            delay(intervalMs)
            postIfEligible()
            if (config.mode == 0) {
                return@launch
            }

            while (isActive) {
                delay(intervalMs)
                postIfEligible()
            }
        }
    }

    private fun postIfEligible() {
        val config = NoticeConfigStore.getVpnReminderConfig()
        if (!canShowVpnReminder(
                config = config,
                appForeground = GateBrowserApplication.get().isAppForeground(),
                notificationsAllowed = HintUtil.coHavePermission(),
            )
        ) {
            return
        }

        runCatching {
            createChannel()
            getManager().notify(NOTIFICATION_ID, createNotification())
            UpDataTool.trackEvent("vpn_notification_sent")
        }.onFailure {
            Log.w("VpnReminderNotifier", "Failed to show VPN reminder", it)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notify_logo)
        .setContentTitle(context.getString(R.string.vpn_reminder_title))
        .setContentText(context.getString(R.string.vpn_reminder_caption))
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(createContentIntent())
        .setCustomContentView(createCollapsedView())
        .setCustomBigContentView(createExpandedView())
        .setCustomHeadsUpContentView(createCollapsedView())
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .build()

    private fun createCollapsedView(): RemoteViews =
        RemoteViews(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                R.layout.notification_vpn_cos
            } else {
                R.layout.notification_vpn_col
            },
        ).also { bindCollapsedView(it) }

    private fun createExpandedView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_vpn_coy).also { bindExpandedView(it) }

    private fun bindCollapsedView(view: RemoteViews) {
        view.setTextViewText(R.id.news_title, context.getString(R.string.vpn_reminder_title))
        view.setTextViewText(R.id.news_action, context.getString(R.string.vpn_reminder_action))
        view.setImageViewResource(R.id.news_fire, R.drawable.ic_notify_exclamation_mark)
        view.setOnClickPendingIntent(R.id.news_action, createContentIntent())
    }

    private fun bindExpandedView(view: RemoteViews) {
        view.setTextViewText(R.id.news_title, context.getString(R.string.vpn_reminder_title))
        view.setTextViewText(R.id.news_caption, context.getString(R.string.vpn_reminder_caption))
        view.setTextViewText(R.id.news_action, context.getString(R.string.vpn_reminder_action))
        view.setImageViewResource(R.id.news_fire, R.drawable.ic_notify_exclamation_mark)
        view.setImageViewResource(R.id.news_artwork, R.drawable.ic_notify_vpn)
        view.setOnClickPendingIntent(R.id.news_action, createContentIntent())
    }

    private fun createContentIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            GuideActivity.createHotStartIntent(context).apply {
                putExtra(EXTRA_OPENED_FROM_VPN_REMINDER, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelReminderTimer() {
        reminderJob?.cancel()
        reminderJob = null
    }

    private fun cancelNotification() {
        getManager().cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            )
                .setName(context.getString(R.string.vpn_reminder_channel))
                .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                .setLightsEnabled(true)
                .setVibrationEnabled(true)
                .setShowBadge(true)
                .build(),
        )
    }

    private fun getManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
