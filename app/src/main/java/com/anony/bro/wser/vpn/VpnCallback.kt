package com.anony.bro.wser.vpn

interface VpnCallback {
    fun onStateChanged(state: VpnState) {}
    fun onTrafficUpdate(
        uploadSpeed: Long,
        downloadSpeed: Long,
        totalUpload: Long,
        totalDownload: Long
    ) {}
    fun onError(message: String) {}
}
