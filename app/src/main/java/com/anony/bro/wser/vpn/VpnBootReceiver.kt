package com.anony.bro.wser.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机广播接收器：设备启动完成后重新拉起 VPN 常驻栏，
 * 对应文档中「开机后能重新拉起常驻栏」的保活要求。
 */
class VpnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            VpnBarLauncher.ensureRunning(context)
        }
    }
}
