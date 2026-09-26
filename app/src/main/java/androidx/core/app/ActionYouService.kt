package androidx.core.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ActionYouService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}