package com.anony.bro.wser.ads

import android.util.Log

object AdLogger {
    private const val TAG = "AdmobAD"

    var enabled = true

    private val presentedCountByType = mutableMapOf<String, Int>()

    fun sdkInitialized() = d("【sdk】初始化成功")

    fun sdkInitFailed(error: Throwable) = e("【sdk】初始化失败", error)

    fun configRefreshed(
        enabled: Boolean,
        appOpen: Boolean?,
        interstitial: Boolean?,
        native: Boolean?,
        rewarded: Boolean?,
        fullJson: String
    ) {
        d(
            "【config】刷新完成 | enabled=$enabled appOpen=${appOpen ?: "-"} " +
                "interstitial=${interstitial ?: "-"} native=${native ?: "-"} rewarded=${rewarded ?: "-"}"
        )
        d("【config】remote=$fullJson")
    }

    fun configDisabled() = d("【config】广告已全局禁用")

    fun configParseFailed(error: Throwable) = e("【config】解析失败，使用默认配置", error)

    fun cacheExpired(adType: String, ip: String?, ageMs: Long, ttlMs: Long) {
        w("【${slotKey(adType)}】缓存失效 | ip=${ip ?: "-"} age=${ageMs}ms ttl=${ttlMs}ms")
    }

    fun interstitialBegin(ip: String, minDelay: Long, skipPreload: Boolean) {
        d("【${slotKey(ip)}】开始流程 | 最小延迟=${minDelay}ms 跳过预加载=$skipPreload")
    }

    fun interstitialLoadRequest(ip: String, state: String) {
        d("【${slotKey(ip)}】加载请求 | 当前状态=$state")
    }

    fun interstitialWaterfallTry(index: Int, unitName: String, unitId: String, ip: String) {
        d("【${slotKey(unitName.ifBlank { ip })}】瀑布流#$index 尝试加载 | 单元=$unitName ID=$unitId")
    }

    fun interstitialLoaded(unitName: String, unitId: String, ip: String) {
        d("【${slotKey(unitName.ifBlank { ip })}】✓ 加载成功 | 单元=$unitName ID=$unitId")
    }

    fun interstitialLoadFailed(unitName: String, ip: String, reason: String) {
        w("【${slotKey(unitName.ifBlank { ip })}】✕ 加载失败 | 单元=$unitName 原因=$reason")
    }

    fun interstitialAllFailed(ip: String) {
        w("【${slotKey(ip)}】✕ 所有瀑布流单元加载失败")
    }

    fun interstitialCacheHit(ip: String) = d("【${slotKey(ip)}】缓存命中")

    fun interstitialCacheMiss(ip: String) = d("【${slotKey(ip)}】缓存未命中 → 开始加载")

    fun interstitialWaitMinDelay(remaining: Long, ip: String) {
        d("【${slotKey(ip)}】广告已就绪，等待最小延迟 | 剩余=${remaining}ms")
    }

    fun interstitialShowRequest(ip: String, state: String, cachedIp: String?) {
        d("【${slotKey(ip)}】展示前检查 | 状态=$state cached=${cachedIp ?: "-"}")
    }

    fun interstitialShowBlocked(reason: String, ip: String?) {
        w("【${slotKey(ip)}】✕ 展示被拦截 | 原因=$reason")
    }

    fun interstitialPresented(ip: String) {
        val slot = slotKey(ip)
        d("【$slot】✓ 展示成功")
        logPresentedCount(slot)
    }

    fun interstitialClosed(ip: String) = d("【${slotKey(ip)}】✓ 用户关闭")

    fun interstitialAutoPreload(ip: String) = d("【${slotKey(ip)}】自动预加载下一条")

    fun interstitialShowFailed(reason: String) = w("【interstitial】✕ 展示失败 | 原因=$reason")

    fun interstitialTimeout(ip: String, timeoutMs: Long) {
        w("【${slotKey(ip)}】⏱ 超时${timeoutMs}ms → 跳过广告并继续流程")
    }

    fun rewardedBegin(ip: String, proUnlocked: Boolean) {
        d("【${slotKey(ip)}】开始流程 | proUnlocked=$proUnlocked")
    }

    fun rewardedBypass(ip: String) = d("【${slotKey(ip)}】跳过广告 | 原因=pro_unlocked")

    fun rewardedCacheHit(ip: String) = d("【${slotKey(ip)}】缓存命中")

    fun rewardedCacheMiss(ip: String) = d("【${slotKey(ip)}】缓存未命中 → 开始加载")

    fun rewardedPresented(ip: String) {
        val slot = slotKey(ip)
        d("【$slot】✓ 展示成功")
        logPresentedCount(slot)
    }

    fun rewardedCompleted(ip: String) = d("【${slotKey(ip)}】✓ 观看完成")

    fun rewardedClosedNoReward(ip: String) = w("【${slotKey(ip)}】关闭后未发奖")

    fun rewardedShowFailed(ip: String, reason: String) {
        w("【${slotKey(ip)}】✕ 展示失败 | 原因=$reason")
    }

    fun rewardedTimeout(ip: String) = w("【${slotKey(ip)}】⏱ 超时")

    fun nativeLoadRequest(slotName: String, ip: String, state: String) {
        d("【${slotKey(slotName)}】加载请求 | 当前状态=$state")
    }

    fun nativeWaterfallTry(slotName: String, index: Int, unitName: String, unitId: String, ip: String) {
        d("【${slotKey(slotName)}】瀑布流#$index 尝试加载 | 单元=$unitName ID=$unitId")
    }

    fun nativeLoaded(slotName: String, unitName: String, unitId: String, ip: String) {
        d("【${slotKey(slotName)}】✓ 加载成功 | 单元=$unitName ID=$unitId")
    }

    fun nativeLoadFailed(slotName: String, unitName: String, ip: String, reason: String) {
        w("【${slotKey(slotName)}】✕ 加载失败 | 单元=$unitName 原因=$reason")
    }

    fun nativeAllFailed(slotName: String, ip: String) {
        w("【${slotKey(slotName)}】✕ 所有瀑布流单元加载失败")
    }

    fun nativePresented(slotName: String, ip: String) {
        val slot = slotKey(slotName)
        d("【$slot】✓ 展示成功")
        logPresentedCount(slot)
    }

    fun nativeDestroyed(slotName: String) = d("【${slotKey(slotName)}】广告已销毁")

    fun bannerCollapsible(slotName: String, collapsible: Boolean) {
        d("【${slotKey(slotName)}】collapsible=$collapsible")
    }

    fun preloadConnectedStart(ip: String) {
        d("【connected_preload】开始 | sequence=inters_connect -> inters_back -> native_common")
    }

    fun preloadConnectedSlot(slot: String, enabled: Boolean, action: String) {
        d("【connected_preload】$slot | enabled=$enabled action=$action")
    }

    fun preloadConnectedRetry(slot: String, attempt: Int, reason: String) {
        w("【connected_preload】$slot retry#$attempt | 原因=$reason")
    }

    fun preloadConnectedSkip(reason: String) = d("【connected_preload】跳过 | 原因=$reason")

    fun preloadConnectedDone() = d("【connected_preload】预加载触发完成")

    fun loadBlocked(adType: String, reason: String) {
        w("【${slotKey(adType)}】加载被拦截 | 原因=$reason")
    }

    fun loadSkipped(adType: String, reason: String) {
        d("【${slotKey(adType)}】跳过加载 | 原因=$reason")
    }

    fun capBlocked(adType: String, action: String, current: Int, limit: Int) {
        w("【${slotKey(adType)}】$action 被次数限制拦截 | 已展示=$current 限制=$limit")
    }

    fun capPresentedCount(adType: String, current: Int, limit: Int) {
        d("【${slotKey(adType)}】当日展示次数更新 | 已展示=$current 限制=$limit")
    }

    fun uiCenterButtonClick(vpnState: String, switching: Boolean) {
        d("【UI】中央按钮点击 | VPN状态=$vpnState 正在切换服务器=$switching")
    }

    fun connectClick() = d("【连接】▶ 用户点击连接")

    fun connectAdStart() = d("【连接】插屏广告开始 | 流程：展示 → 连接成功")

    fun connectAdDone() = d("【连接】插屏广告完成 → 渲染连接成功界面 + 打开状态页")

    fun connectRendered() = d("【连接】✓ 连接成功界面已渲染（无广告）")

    fun connectCallbackHandled() = d("【连接】✓ CONNECTED回调已处理")

    fun disconnectAdStart() = d("【断开】插屏广告开始 | 流程：展示 → 断开连接")

    fun disconnectAdDone() = d("【断开】插屏广告完成 → 执行断开操作")

    fun switchBegin(nodeKey: String) = d("【切换】▶ 开始切换服务器 | 节点=$nodeKey")

    fun switchAdStart() = d("【切换】插屏广告开始 | 流程：展示 → 断开 → 重连")

    fun switchAdDone() = d("【切换】插屏广告完成 → 执行断开操作")

    fun vipUnlockStart(nodeKey: String, vpnState: String) {
        d("【VIP解锁】开始流程 | 节点=$nodeKey VPN状态=$vpnState")
    }

    fun vipUnlockAd() = d("【VIP解锁】开始激励广告")

    fun vipUnlockSuccess() = d("【VIP解锁】✓ 解锁成功")

    fun vipUnlockPresented() = d("【VIP解锁】激励广告已展示")

    fun vipUnlockFailed() = w("【VIP解锁】✕ 解锁失败")

    fun vipLoadingShow() = d("【VIP解锁】显示加载遮罩")

    fun openStatusPage() = d("【UI】打开连接状态页")

    private fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    private fun w(msg: String) {
        if (enabled) Log.w(TAG, msg)
    }

    private fun e(msg: String, error: Throwable? = null) {
        if (!enabled) return
        if (error != null) {
            Log.e(TAG, msg, error)
        } else {
            Log.e(TAG, msg)
        }
    }

    private fun logPresentedCount(adType: String) {
        val slot = slotKey(adType)
        val next = (presentedCountByType[slot] ?: 0) + 1
        presentedCountByType[slot] = next
        d("【$slot】累计展示次数=$next")
    }

    private fun slotKey(raw: String?): String {
        val value = raw.orEmpty()
        return when {
            value.startsWith("inters_connect") || value == "INTERSTITIAL_SHARED" -> "inters_connect"
            value.startsWith("open_back") || value == "BACK_INTERSTITIAL_SHARED" -> "open_back"
            value.startsWith("inters_guide_1") -> "inters_guide_1"
            value.startsWith("inters_guide_2") -> "inters_guide_2"
            value.startsWith("inters_home") -> "inters_home"
            value.startsWith("inters_back") -> "inters_back"
            value == "native_connect_native" || value.startsWith("connect_native") || value == "RESULT_SHARED" -> "connect_native"
            value.startsWith("native_") -> value.removePrefix("native_")
            value.isBlank() -> "unknown_slot"
            else -> value
        }
    }
}
