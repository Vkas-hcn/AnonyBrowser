package com.anony.bro.wser.ads

import com.anony.bro.wser.vpn.VpnState


/**
 * 单广告位缓存：最多缓存 1 个广告。
 *
 * - put() 会覆盖之前的缓存
 * - 当前版本不再做 IP 绑定校验
 */
class IpBoundSingleCache<T> {
    private var wrapper: AdWrapper<T>? = null

    fun put(
        ad: T,
        slotId: String,
        ipKey: String,
        loadTime: Long,
        vpnLoadStatus: VpnState,
        vpnSessionId: Long
    ) {
        wrapper = AdWrapper(
            ad = ad,
            slotId = slotId,
            ipKey = ipKey,
            loadTime = loadTime,
            vpnLoadStatus = vpnLoadStatus,
            vpnSessionId = vpnSessionId
        )
    }

    fun getIfIpMatch(ipKey: String): T? {
        val w = wrapper ?: return null
        return w.ad
    }

    fun getWrapper(): AdWrapper<T>? = wrapper

    fun isLoadedForIp(ipKey: String): Boolean = wrapper != null

    fun isExpired(nowMs: Long, ttlMs: Long): Boolean {
        val w = wrapper ?: return false
        return nowMs - w.loadTime >= ttlMs
    }

    fun isValid(ipKey: String, nowMs: Long, ttlMs: Long): Boolean {
        val w = wrapper ?: return false
        return nowMs - w.loadTime < ttlMs
    }

    fun ageMs(nowMs: Long): Long? {
        val w = wrapper ?: return null
        return nowMs - w.loadTime
    }

    fun clear() {
        wrapper = null
    }
}

