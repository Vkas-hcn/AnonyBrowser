package com.anony.bro.wser.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class TimeWorkerRe: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
//        Log.e("通知弹窗", "收到闹钟: ", )
        Log.i("NewsNotification", "alarm broadcast received: action=${intent?.action}, contextPresent=${context != null}")
        val pendingResult = goAsync()
        HintUtil.sendTthree {
            Log.d("NewsNotification", "alarm broadcast async work finished")
            pendingResult.finish()
        }
        HintUtil.markLing()
    }
}
