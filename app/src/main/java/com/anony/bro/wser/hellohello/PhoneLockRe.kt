package com.anony.bro.wser.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PhoneLockRe: BroadcastReceiver() {
    override fun onReceive(p0: Context?, p1: Intent?) {
        Log.i(TAG, "broadcast received: action=${p1?.action}, contextPresent=${p0 != null}")
        p0 ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "unlock notification delay started: 1500ms")
            delay(1_500)
            Log.d(TAG, "unlock notification delay finished")
            HintUtil.sendTtwo {
                Log.d(TAG, "unlock notification work finished")
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NewsNotification"
    }
}
