package com.anony.bro.wser.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 常驻栏拉起入口。
 *
 * 以「仅展示常驻栏」的动作启动 [WebloraVpnService]，用于应用启动、开机、任务被移除后等场景。
 * 该调用是幂等的：服务已在运行时只会再次触发一次 onStartCommand，不会重复建立隧道。
 */
object VpnBarLauncher {

    private const val TAG = "VpnBarLauncher"

    fun ensureRunning(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, WebloraVpnService::class.java)
            .setAction(WebloraVpnService.ACTION_SHOW_BAR)
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onFailure { Log.w(TAG, "ensureRunning failed", it) }
    }

    fun refreshNewsConfig(context: Context) {
        if (VpnManager.activeService == null) return
        val appContext = context.applicationContext
        val intent = Intent(appContext, WebloraVpnService::class.java)
            .setAction(WebloraVpnService.ACTION_REFRESH_NEWS_CONFIG)
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onFailure { Log.w(TAG, "refreshNewsConfig failed", it) }
    }
}
