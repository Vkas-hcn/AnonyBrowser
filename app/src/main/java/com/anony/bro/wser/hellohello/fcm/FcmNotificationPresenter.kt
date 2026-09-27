package com.anony.bro.wser.hellohello.fcm

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anony.bro.wser.R
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.LoadingTracking
import com.anony.bro.wser.view.guide.GuideActivity

internal object FcmNotificationPresenter {
    internal const val CHANNEL_ID = "fcm_news_channel_v1"
    internal const val NOTIFICATION_ID = 90_001
    private const val TAG = "FcmNews"

    fun show(content: FcmNewsContent, soundMode: Int): Boolean {
        val context = GateBrowserApplication.get()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "FCM 提交取消：内容ID=${content.id}, 原因=无 POST_NOTIFICATIONS 权限")
            return false
        }
        createChannel()
        val silent = soundMode == 1
        Log.i(
            TAG,
            "FCM 准备提交：通知ID=$NOTIFICATION_ID, 渠道=$CHANNEL_ID, " +
                "内容ID=${content.id}, 声音模式=$soundMode, 静音=${if (silent) "是" else "否"}",
        )
        val clickIntent = GuideActivity.createHotStartIntent(context).apply {
            action = Intent.ACTION_VIEW
            content.link.takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
            putExtra(LoadingTracking.EXTRA_FROM_FCM, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "FCM 已调用系统通知提交接口：通知ID=$NOTIFICATION_ID, 内容ID=${content.id}")
            true
        }.onFailure { error ->
            Log.e(TAG, "FCM 提交失败：通知ID=$NOTIFICATION_ID, 内容ID=${content.id}", error)
        }.getOrDefault(false)
    }

    private fun createChannel() {
        val context = GateBrowserApplication.get()
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_news))
                .build(),
        )
    }
}
