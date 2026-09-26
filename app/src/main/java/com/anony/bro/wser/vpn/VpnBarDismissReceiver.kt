package com.anony.bro.wser.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class VpnBarDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        Log.d(TAG, "vpn bar dismissed; restoring")
        VpnBarLauncher.ensureRunning(context)
    }

    private companion object {
        const val TAG = "VpnBarLauncher"
    }
}
