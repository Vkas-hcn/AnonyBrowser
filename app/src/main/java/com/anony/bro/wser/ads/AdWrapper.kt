package com.anony.bro.wser.ads

import com.anony.bro.wser.vpn.VpnState


/**
 * 广告包装器：记录广告实例和加载时间。
 */
data class AdWrapper<T>(
    val ad: T,
    val slotId: String,
    val ipKey: String,
    val loadTime: Long,
    val vpnLoadStatus: VpnState,
    val vpnSessionId: Long
)

