package com.anony.bro.wser.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NewsBannerDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val notificationId = intent?.getIntExtra(HintUtil.CO_NOTIFICATION_ID, -1) ?: return
        val showType = intent.getIntExtra(HintUtil.CO_SHOW_TYPE, -1)
        if (notificationId < 0 || showType < 0) return
        Log.d(TAG, "banner dismiss received: id=$notificationId, type=$showType")
        HintUtil.restorePersistentBannerIfActive(notificationId, showType)
    }

    private companion object {
        const val TAG = "NewsNotification"
    }
}
