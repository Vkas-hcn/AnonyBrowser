package com.anony.bro.wser.hellohello

import android.content.Context
import android.content.Intent
import androidx.core.app.WebCanService

class AssistanceLaunceJob: WebCanService() {
    override fun onHandleWork(intent: Intent) {
        HintUtil.launchFg(true)
    }

    companion object{

        fun enqueueWork(context: Context){
            enqueueWork(context, AssistanceLaunceJob::class.java,4321,Intent(context, AssistanceLaunceJob::class.java))
        }
    }
}