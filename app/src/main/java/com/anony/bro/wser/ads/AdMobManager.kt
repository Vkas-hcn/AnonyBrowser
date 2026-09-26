package com.anony.bro.wser.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.anony.bro.wser.ads.AdConfig
import com.anony.bro.wser.ads.AdConfigSerializer
import com.anony.bro.wser.ads.AdState
import com.anony.bro.wser.ads.AdTypeConfig
import com.anony.bro.wser.ads.AdUnit
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.anony.bro.wser.R
import com.anony.bro.wser.data.ref.AdTrackingHelper
import com.anony.bro.wser.databinding.ItemResultNativeAdBinding

import com.anony.bro.wser.data.vpn.VpnConfigFactory
import com.anony.bro.wser.data.vpn.VpnServerCatalogRepository
import com.anony.bro.wser.vpn.VpnManager
import com.anony.bro.wser.vpn.VpnState
import java.util.concurrent.CopyOnWriteArrayList

object AdMobManager {
    enum class NativePlacement {
        HOME,
        CONNECT,
    }

    enum class InterstitialPlacement(val id: String, val usesOpenCap: Boolean = false) {
        GUIDE_1("inters_guide_1", true),
        GUIDE_2("inters_guide_2", true),
        HOME("inters_home"),
        BACK("inters_back"),
        SEARCH_BACK("search_inters_back"),
    }

    private const val PRELOAD_RETRY_DELAY_MS = 900L
    private const val PRELOAD_MAX_RETRY = 5
    private const val AD_CACHE_TTL_MS = 3000_000L // 50分钟


    // ========= English identifiers (no hardcoded Chinese strings) =========
    private const val AD_TYPE_INTERSTITIAL = "inters_connect"
    private const val AD_TYPE_OPEN = "open_back"
    private const val AD_TYPE_NATIVE_COMMON = "connect_native"
    private const val AD_TYPE_NATIVE_HOME = "home_native"
    private const val AD_TYPE_BANNER = "bar_banner"

    private const val ERROR_SDK_NOT_INITIALIZED = "SDK not initialized"
    private const val ERROR_AD_DISABLED_BY_CONFIG = "Ad disabled by config"
    private const val ERROR_APP_IN_BACKGROUND = "App is in background"
    private const val ERROR_ANOTHER_AD_SHOWING = "Another ad is currently showing"
    private const val ERROR_AD_NOT_LOADED = "Ad not loaded"
    private const val ERROR_INVALID_ACTIVITY = "Invalid Activity"
    private const val ERROR_INTERSTITIAL_DAILY_LIMIT = "Interstitial daily limit reached"
    private const val ERROR_BANNER_DAILY_LIMIT = "Banner daily limit reached"
    private const val ERROR_ALL_INTERSTITIAL_FAILED = "Failed to load all interstitial ad units"
    private const val NATIVE_CONTAINER_TAG_PREFIX = "admob_native_"

    private const val INTERSTITIAL_SHARED_KEY = "INTERSTITIAL_SHARED"
    private const val OPEN_AD_SHARED_KEY = "BACK_INTERSTITIAL_SHARED"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sdkInitialized = false
    private val sdkInitializationCallbacks = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var config = defaultConfig()

    @Volatile
    private var lastConfigJson: String = ""

    @Volatile
    private var appForeground = true

    @Volatile
    private var interstitialAdState = AdState.NOT_LOADED
    private val interstitialCache = IpBoundSingleCache<InterstitialAd>()

    @Volatile
    private var interstitialShownIpKey: String? = null

    @Volatile
    private var interstitialLoadToken: Long = 0L

    @Volatile
    private var interstitialCurrentIndex = 0


    @Volatile
    private var interstitialPosId: String = AD_TYPE_INTERSTITIAL

    @Volatile
    private var interstitialRequestStartMs: Long = 0L

    @Volatile
    private var interstitialShowStartMs: Long = 0L

    @Volatile
    private var openAdState = AdState.NOT_LOADED
    private val openAdCache = IpBoundSingleCache<AppOpenAd>()

    @Volatile
    private var openAdShownIpKey: String? = null

    @Volatile
    private var openAdLoadToken: Long = 0L

    @Volatile
    private var openAdCurrentIndex = 0


    @Volatile
    private var openAdPosId: String = AD_TYPE_OPEN

    @Volatile
    private var openAdRequestStartMs: Long = 0L

    @Volatile
    private var openAdShowStartMs: Long = 0L

    private val nativeResultSlot = NativeAdSlot(AD_TYPE_NATIVE_COMMON, mainHandler)
    private val nativeHomeSlot = NativeAdSlot(AD_TYPE_NATIVE_HOME, mainHandler)
    private val bannerSlot = BannerAdSlot(AD_TYPE_BANNER, mainHandler)
    private val bannerLoadObservers =
        enumValues<BannerAdSlot.BannerMode>().associateWith { CopyOnWriteArrayList<AdLoadListener>() }

    private data class PlacementSlot(
        var state: AdState = AdState.NOT_LOADED,
        var ad: InterstitialAd? = null,
        var requestId: String? = null,
        var posId: String? = null,
        var loadedAt: Long = 0L,
        var loading: Boolean = false,
    )

    private val placementSlots = InterstitialPlacement.entries.associateWith { PlacementSlot() }
    private val guideShowLock = Any()
    private var guideShown = false

    @Volatile
    private var vpnSessionCounter: Long = 0L

    @Volatile
    private var vpnSessionActive = false

    fun initialize(context: Context, onComplete: (Boolean) -> Unit) {
        if (sdkInitialized) {
            postMain { onComplete(true) }
            return
        }
        sdkInitializationCallbacks += onComplete
        if (appContext != null) return
        try {
            appContext = context.applicationContext
            MobileAds.initialize(context.applicationContext) {
                sdkInitialized = true
                AdLogger.sdkInitialized()
                dispatchSdkInitializationResult(true)
            }
            refreshConfig()
        } catch (t: Throwable) {
            sdkInitialized = false
            AdLogger.sdkInitFailed(t)
            dispatchSdkInitializationResult(false)
        }
    }

    fun runWhenInitialized(callback: (Boolean) -> Unit) {
        if (sdkInitialized) {
            postMain { callback(true) }
        } else {
            initialize(appContext ?: return, callback)
        }
    }

    /**
     * 刷新 AdMob 广告配置
     *
     * 从远程配置获取最新的 AdMob 配置，如果配置发生变化则更新本地配置并清理相应广告缓存。
     * 当检测到配置中的广告单元设置发生变更时，会清除对应类型的广告缓存并重置加载状态，
     * 确保后续加载的广告使用新的配置参数。
     *
     * 处理流程：
     * 1. 获取远程配置 JSON，如果与上次配置相同则跳过刷新
     * 2. 解析新配置并记录日志
     * 3. 如果广告总开关关闭，则仅更新配置后返回
     * 4. 对比各广告类型配置是否变化，如有变化则清除对应缓存和状态
     */
    fun refreshConfig() {
        // 获取远程配置并检查是否有变化
        val json = VpnServerCatalogRepository.cachedOrEmpty()
            .adConfig
            .ifBlank { VpnConfigFactory.admobConfig }
        if (json == lastConfigJson) {
            return
        }
        // 解析新配置并记录刷新日志
        val parsed = parseConfig(json)
        AdLogger.configRefreshed(
            parsed.enabled,
            null, // removed app_open support
            parsed.intersConnectConfig?.enabled,
            parsed.nativeCommonConfig?.enabled,
            null,
            json
        )
        val oldConfig = config
        lastConfigJson = json
        config = parsed

        // 如果广告总开关已关闭，记录日志后返回
        if (!parsed.enabled) {
            AdLogger.configDisabled()
            return
        }
        // 插屏广告配置变更时，清除缓存并重置加载状态
        if (!oldConfig.intersConnectConfig.isEquivalent(parsed.intersConnectConfig)) {
            interstitialCache.clear()
            interstitialAdState = AdState.NOT_LOADED
        }
        if (!oldConfig.intersBackConfig.isEquivalent(parsed.intersBackConfig)) {
            openAdCache.clear()
            openAdState = AdState.NOT_LOADED
        }
        // 激励视频广告配置变更时，清除广告实例并重置加载状态
        // 引导页原生广告配置变更时，清除广告槽位
        // 结果页原生广告配置变更时，清除广告槽位
        if (!oldConfig.nativeCommonConfig.isEquivalent(parsed.nativeCommonConfig)) {
            nativeResultSlot.clear()
        }
        if (!oldConfig.homeNativeConfig.isEquivalent(parsed.homeNativeConfig)) {
            nativeHomeSlot.clear()
        }
        if (!oldConfig.barBannerConfig.isEquivalent(parsed.barBannerConfig)) {
            bannerSlot.clear()
        }
        InterstitialPlacement.entries.forEach { placement ->
            if (!oldConfig.placementConfig(placement)
                    .isEquivalent(parsed.placementConfig(placement))
            ) {
                placementSlots.getValue(placement).apply {
                    ad = null; requestId = null; posId = null; state = AdState.NOT_LOADED; loading =
                    false
                }
            }
        }
    }

    fun isAdEnabled(): Boolean = sdkInitialized && config.enabled

    fun getIntersConnectLoadTimeoutMs(): Long {
        return config.intersConnectLoadTime.coerceAtLeast(0).toLong()
    }

    fun getIntersOpenLoadTimeoutMs(): Long {
        return config.intersOpenLoadTime.coerceAtLeast(0).toLong()
    }

    fun isAnyAdShowing(): Boolean =
        interstitialAdState == AdState.SHOWING ||
                openAdState == AdState.SHOWING

    fun isAppInForeground(): Boolean = appForeground

    fun updateAppForegroundState(isForeground: Boolean) {
        appForeground = isForeground
    }

    fun clearInterstitialCache() {
        interstitialLoadToken++
        interstitialCache.clear()
        interstitialAdState = AdState.NOT_LOADED
    }

    fun onVpnConnectedStable() {
        if (vpnSessionActive) return
        vpnSessionCounter++
        vpnSessionActive = true
    }

    fun onVpnDisconnected() {
        vpnSessionActive = false
    }

    fun clearAllAdsForRealDisconnect() {
        // VPN 真实断开只清 inters_connect；open_back 跨会话保留。
        interstitialLoadToken++
        interstitialCache.clear()
        interstitialAdState = AdState.NOT_LOADED
        interstitialShownIpKey = null
    }

    private fun ensureVpnSessionActive() {
        if (vpnSessionActive) return
        if (VpnManager.state != VpnState.CONNECTED) return
        vpnSessionCounter++
        vpnSessionActive = true
    }

    private fun currentVpnSessionId(): Long {
        ensureVpnSessionActive()
        return vpnSessionCounter
    }

    private fun isConnectedStableForAds(): Boolean = VpnManager.state == VpnState.CONNECTED

    private fun isWrapperReusable(wrapper: AdWrapper<*>?, slotId: String, now: Long): Boolean {
        wrapper ?: return false
        if (wrapper.slotId != slotId) return false
        if (wrapper.vpnLoadStatus != VpnState.CONNECTED) return false
        if (wrapper.vpnSessionId != currentVpnSessionId()) return false
        if (now - wrapper.loadTime >= AD_CACHE_TTL_MS) return false
        return true
    }

    private fun isWrapperReusable(wrapper: AdWrapper<*>?, slotId: String): Boolean =
        isWrapperReusable(wrapper, slotId, System.currentTimeMillis())

    private fun isNativeWrapperReusable(
        wrapper: AdWrapper<*>?,
        slotId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        wrapper ?: return false
        return wrapper.slotId == slotId && now - wrapper.loadTime < AD_CACHE_TTL_MS
    }

    fun isInterstitialAdLoadedForIp(ipKey: String): Boolean {
        return interstitialAdState == AdState.LOADED &&
                isWrapperReusable(interstitialCache.getWrapper(), AD_TYPE_INTERSTITIAL)
    }

    /**
     * 加载插屏广告
     *
     * 检查每日展示次数限制、缓存状态和加载状态，按需加载插屏广告。
     * 如果已有有效的缓存广告则直接返回，避免重复加载。
     * 使用瀑布流方式加载广告，支持多个广告单元依次尝试。
     *
     * @param context Android 上下文对象，用于广告加载
     * @param _ipKey IP 键值（内部统一使用共享键，此参数保留用于接口兼容）
     * @param listener 广告加载监听器，加载成功或失败时回调
     */
    fun loadInterstitialAd(context: Context, _ipKey: String, listener: AdLoadListener?) {
        val ipKey = INTERSTITIAL_SHARED_KEY
        // 检查是否超过每日展示次数限制
        if (!canLoadOrShowInterstitial(context.applicationContext)) {
            val count = InterstitialDailyCapStore.getTodayCount(context.applicationContext)
            AdLogger.capBlocked(AD_TYPE_INTERSTITIAL, "加载", count, config.interstitialDailyLimit)
            postMain { listener?.onAdFailedToLoad(ERROR_INTERSTITIAL_DAILY_LIMIT) }
            return
        }
        val typeConfig = config.intersConnectConfig
        val now = System.currentTimeMillis()
        val wrapper = interstitialCache.getWrapper()
        AdLogger.interstitialLoadRequest(ipKey, interstitialAdState.toString())
        // 检查是否有有效的缓存广告，如有则直接返回
        if (interstitialAdState == AdState.LOADED &&
            isWrapperReusable(wrapper, AD_TYPE_INTERSTITIAL, now)
        ) {
            AdLogger.interstitialCacheHit(ipKey)
            postMain { listener?.onAdLoaded() }
            return
        }
        // 如果正在加载中，直接返回避免重复加载
        if (interstitialAdState == AdState.LOADING) {
            AdLogger.loadSkipped(AD_TYPE_INTERSTITIAL, "already_loading")
            return
        }
        // 清除已过期的缓存广告
        if (wrapper != null) {
            val age = interstitialCache.ageMs(now) ?: 0L
            if (!isWrapperReusable(wrapper, AD_TYPE_INTERSTITIAL, now)) {
                AdLogger.cacheExpired(AD_TYPE_INTERSTITIAL, wrapper.ipKey, age, AD_CACHE_TTL_MS)
            }
            interstitialCache.clear()
            interstitialAdState = AdState.NOT_LOADED
        }
        // 检查是否可以开始加载广告
        if (!canStartLoad(AD_TYPE_INTERSTITIAL, typeConfig?.enabled == true, listener)) return
        // 更新加载令牌和状态，开始瀑布流加载
        val token = ++interstitialLoadToken
        interstitialAdState = AdState.LOADING
        interstitialCurrentIndex = 0
        loadInterstitialWaterfall(
            context = context.applicationContext,
            units = typeConfig!!.adUnits,
            ipKey = ipKey,
            vpnSessionId = currentVpnSessionId(),
            token = token,
            listener = listener
        )
    }

    /**
     * 显示插屏广告
     *
     * 检查每日展示次数限制、广告缓存状态和显示条件，满足条件后展示插屏广告。
     * 广告显示期间会设置全屏沉浸模式，并在关闭后自动预加载下一个广告。
     * 完整跟踪广告的展示、点击、关闭等事件用于数据统计。
     *
     * @param activity Android Activity 对象，用于显示广告
     * @param _ipKey IP 键值（内部统一使用共享键，此参数保留用于接口兼容）
     * @param listener 广告显示监听器，展示成功、失败、点击、关闭时回调
     * @param skipPostShowPreload 是否跳过展示后的自动预加载，默认为 false
     * @return 是否成功开始显示广告，true 表示广告已开始显示，false 表示显示失败
     */
    fun showInterstitialAd(
        activity: Activity,
        _ipKey: String,
        listener: AdShowListener?,
        skipPostShowPreload: Boolean = false
    ): Boolean {
        val ipKey = INTERSTITIAL_SHARED_KEY
        // 检查是否超过每日展示次数限制
        if (!canLoadOrShowInterstitial(activity.applicationContext)) {
            val count = InterstitialDailyCapStore.getTodayCount(activity.applicationContext)
            AdLogger.capBlocked(AD_TYPE_INTERSTITIAL, "展示", count, config.interstitialDailyLimit)
            postMain { listener?.onAdShowFailed(ERROR_INTERSTITIAL_DAILY_LIMIT) }
            return false
        }
        val wrapper = interstitialCache.getWrapper()
        val cachedIp = wrapper?.ipKey
        AdLogger.interstitialShowRequest(ipKey, interstitialAdState.toString(), cachedIp)
        // 检查是否有缓存的广告，如果没有则返回失败
        if (!isWrapperReusable(wrapper, AD_TYPE_INTERSTITIAL)) {
            interstitialCache.clear()
            interstitialAdState = AdState.NOT_LOADED
            postMain { listener?.onAdShowFailed(ERROR_AD_NOT_LOADED) }
            return false
        }
        val cachedAd = wrapper?.ad ?: run {
            postMain { listener?.onAdShowFailed(ERROR_AD_NOT_LOADED) }
            return false
        }
        // 检查是否可以显示广告（前台状态、无其他广告显示等）
        if (!canShowCommon(
                AD_TYPE_INTERSTITIAL,
                activity,
                interstitialAdState == AdState.LOADED,
                listener
            )
        ) return false
        val ad = cachedAd
        interstitialAdState = AdState.SHOWING
        interstitialShownIpKey = ipKey
        // 设置广告的全屏内容回调，处理展示、点击、关闭、失败等事件
        ad.fullScreenContentCallback = createFullscreenCallback(
            onShow = {
                interstitialShowStartMs = System.currentTimeMillis()
                // 更新每日展示计数并记录日志
                InterstitialDailyCapStore.onPresented(
                    appContext ?: activity.applicationContext,
                    config.interstitialDailyLimit
                )
                val count = InterstitialDailyCapStore.getTodayCount(
                    appContext ?: activity.applicationContext
                )
                AdLogger.capPresentedCount(
                    AD_TYPE_INTERSTITIAL,
                    count,
                    config.interstitialDailyLimit
                )
                postMain { listener?.onAdShowed() }
            },
            onClick = {

                postMain { listener?.onAdClicked() }
            },
            onDismiss = {
                // 重置广告状态并清除缓存
                interstitialAdState = AdState.CLOSED
                interstitialCache.clear()
                interstitialAdState = AdState.NOT_LOADED
                val shownIp = interstitialShownIpKey
                interstitialShownIpKey = null
                // 根据配置决定是否在关闭后自动预加载下一个广告
                if (!skipPostShowPreload && shownIp != null) {
                    AdLogger.interstitialAutoPreload(shownIp)
                    loadInterstitialAd(activity.applicationContext, shownIp, null)
                }

                postMain { listener?.onAdClosed() }
            },
            onFail = { err ->

                // 重置广告状态并清除缓存
                interstitialAdState = AdState.NOT_LOADED
                interstitialCache.clear()
                interstitialShownIpKey = null
                postMain { listener?.onAdShowFailed(err) }
            }
        )
        ad.show(activity)
        return true
    }
    fun loadInterstitial(
        context: Context,
        placement: InterstitialPlacement,
        listener: AdLoadListener? = null
    ) {
        val type = config.placementConfig(placement)
        val slot = placementSlots.getValue(placement)
        AdLogger.interstitialLoadRequest(placement.id, slot.state.name)
        if (!canLoadPlacement(context, placement, type, listener)) return
        if (slot.state == AdState.LOADED && System.currentTimeMillis() - slot.loadedAt < AD_CACHE_TTL_MS) {
            AdLogger.interstitialCacheHit(placement.id)
            postMain { listener?.onAdLoaded() }
            return
        }
        if (slot.loading) {
            AdLogger.loadSkipped(placement.id, "already_loading")
            return
        }
        slot.loading = true
        slot.state = AdState.LOADING
        loadPlacementWaterfall(context.applicationContext, placement, type!!.adUnits, 0, listener)
    }

    fun isInterstitialLoaded(placement: InterstitialPlacement): Boolean {
        val slot = placementSlots.getValue(placement)
        return slot.state == AdState.LOADED && slot.ad != null && System.currentTimeMillis() - slot.loadedAt < AD_CACHE_TTL_MS
    }

    fun showInterstitial(
        activity: Activity,
        placement: InterstitialPlacement,
        listener: AdShowListener? = null
    ): Boolean {
        val slot = placementSlots.getValue(placement)
        val ad = slot.ad
        AdLogger.interstitialShowRequest(
            placement.id,
            slot.state.name,
            if (ad == null) null else placement.id
        )
        if (!isInterstitialLoaded(placement) || ad == null || isAnyAdShowing() || activity.isFinishing || activity.isDestroyed) {
            AdLogger.interstitialShowBlocked(ERROR_AD_NOT_LOADED, placement.id)
            postMain { listener?.onAdShowFailed(ERROR_AD_NOT_LOADED) }
            return false
        }
        val limit =
            if (placement.usesOpenCap) config.openDailyLimit else config.interstitialDailyLimit
        val capSlot = if (placement.usesOpenCap) AD_TYPE_OPEN else "interstitial"
        if (!InterstitialDailyCapStore.canLoadOrShow(activity.applicationContext, limit, capSlot)) {
            AdLogger.capBlocked(
                placement.id,
                "show",
                InterstitialDailyCapStore.getTodayCount(activity.applicationContext, capSlot),
                limit
            )
            postMain { listener?.onAdShowFailed(ERROR_INTERSTITIAL_DAILY_LIMIT) }
            return false
        }
        slot.state = AdState.SHOWING
        ad.fullScreenContentCallback = createFullscreenCallback(
            onShow = {
                InterstitialDailyCapStore.onPresented(activity.applicationContext, limit, capSlot)
                AdLogger.interstitialPresented(placement.id)

                postMain { listener?.onAdShowed() }
            },
            onClick = {

                postMain { listener?.onAdClicked() }
            },
            onDismiss = {
                slot.ad = null; slot.state = AdState.NOT_LOADED; slot.loading = false
                AdLogger.interstitialClosed(placement.id)

                slot.requestId = null; slot.posId = null
                AdLogger.interstitialAutoPreload(placement.id)
                loadInterstitial(activity.applicationContext, placement)
                postMain { listener?.onAdClosed() }
            },
            onFail = { error ->
                slot.ad = null; slot.requestId = null; slot.posId = null; slot.state =
                AdState.NOT_LOADED; slot.loading = false
                AdLogger.interstitialShowFailed("${placement.id}: $error")
                postMain { listener?.onAdShowFailed(error) }
            },
        )
        ad.show(activity)
        return true
    }

    /** Starts a new Guide lifecycle and atomically shows its highest-priority ready ad. */
    fun resetGuideShowState() = synchronized(guideShowLock) { guideShown = false }

    fun showGuideAdIfReady(activity: Activity, listener: AdShowListener): Boolean =
        synchronized(guideShowLock) {
            if (guideShown) return@synchronized false
            val placement = when {
                isOpenAdLoaded() -> null
                isInterstitialLoaded(InterstitialPlacement.GUIDE_1) -> InterstitialPlacement.GUIDE_1
                isInterstitialLoaded(InterstitialPlacement.GUIDE_2) -> InterstitialPlacement.GUIDE_2
                else -> return@synchronized false
            }
            val shown = placement?.let { showInterstitial(activity, it, listener) }
                ?: showOpenAd(activity, listener)
            if (shown) guideShown = true
            shown
        }

    fun loadOpenAd(context: Context, listener: AdLoadListener?) {
        val ipKey = OPEN_AD_SHARED_KEY
        if (!canLoadOrShowOpenAd(context.applicationContext)) {
            val count = InterstitialDailyCapStore.getTodayCount(
                context.applicationContext,
                AD_TYPE_OPEN,
            )
            AdLogger.capBlocked(
                AD_TYPE_OPEN,
                "load",
                count,
                config.openDailyLimit
            )
            postMain { listener?.onAdFailedToLoad(ERROR_INTERSTITIAL_DAILY_LIMIT) }
            return
        }
        val typeConfig = config.intersBackConfig
        val now = System.currentTimeMillis()
        val wrapper = openAdCache.getWrapper()
        if (openAdState == AdState.LOADED &&
            isOpenAdWrapperReusable(wrapper, now)
        ) {
            postMain { listener?.onAdLoaded() }
            return
        }
        if (openAdState == AdState.LOADING) {
            AdLogger.loadSkipped(AD_TYPE_OPEN, "already_loading")
            return
        }
        if (wrapper != null) {
            openAdCache.clear()
            openAdState = AdState.NOT_LOADED
        }
        if (!canStartOpenAdLoad(typeConfig?.enabled == true, listener)) return
        val token = ++openAdLoadToken
        openAdState = AdState.LOADING
        openAdCurrentIndex = 0
        loadOpenAdWaterfall(
            context = context.applicationContext,
            units = typeConfig!!.adUnits,
            ipKey = ipKey,
            vpnSessionId = 0L,
            token = token,
            listener = listener
        )
    }

    fun isOpenAdLoaded(): Boolean {
        return openAdState == AdState.LOADED &&
                isOpenAdWrapperReusable(openAdCache.getWrapper())
    }

    fun showOpenAd(activity: Activity, listener: AdShowListener?): Boolean {
        val ipKey = OPEN_AD_SHARED_KEY
        if (!canLoadOrShowOpenAd(activity.applicationContext)) {
            val count = InterstitialDailyCapStore.getTodayCount(
                activity.applicationContext,
                AD_TYPE_OPEN,
            )
            AdLogger.capBlocked(
                AD_TYPE_OPEN,
                "show",
                count,
                config.openDailyLimit
            )
            postMain { listener?.onAdShowFailed(ERROR_INTERSTITIAL_DAILY_LIMIT) }
            return false
        }
        val wrapper = openAdCache.getWrapper()
        if (!isOpenAdWrapperReusable(wrapper)) {
            openAdCache.clear()
            openAdState = AdState.NOT_LOADED
            postMain { listener?.onAdShowFailed(ERROR_AD_NOT_LOADED) }
            return false
        }
        val cachedAd = wrapper?.ad ?: run {
            postMain { listener?.onAdShowFailed(ERROR_AD_NOT_LOADED) }
            return false
        }
        if (!canShowOpenAd(activity, openAdState == AdState.LOADED, listener)) return false

        openAdState = AdState.SHOWING
        openAdShownIpKey = ipKey
        cachedAd.fullScreenContentCallback = createFullscreenCallback(
            onShow = {
                openAdShowStartMs = System.currentTimeMillis()
                InterstitialDailyCapStore.onPresented(
                    appContext ?: activity.applicationContext,
                    config.openDailyLimit,
                    AD_TYPE_OPEN,
                )
                val count = InterstitialDailyCapStore.getTodayCount(
                    appContext ?: activity.applicationContext,
                    AD_TYPE_OPEN,
                )
                AdLogger.capPresentedCount(
                    AD_TYPE_OPEN,
                    count,
                    config.openDailyLimit
                )
                postMain { listener?.onAdShowed() }
            },
            onClick = {

                postMain { listener?.onAdClicked() }
            },
            onDismiss = {
                openAdState = AdState.CLOSED
                openAdCache.clear()
                openAdState = AdState.NOT_LOADED
                val shownIp = openAdShownIpKey
                openAdShownIpKey = null
                if (shownIp != null) {
                    AdLogger.interstitialAutoPreload(shownIp)
                    loadOpenAd(activity.applicationContext, null)
                }
                postMain { listener?.onAdClosed() }
            },
            onFail = { err ->
                openAdState = AdState.NOT_LOADED
                openAdCache.clear()
                openAdShownIpKey = null
                postMain { listener?.onAdShowFailed(err) }
            }
        )
        cachedAd.show(activity)
        return true
    }

    // ========== 原生广告 - 结果页位 ==========

    fun loadNativeAd(
        context: Context,
        placement: NativePlacement,
        listener: NativeAdLoadListener?,
    ) {
        val slotId = nativeSlotId(placement)
        val nativeSlot = nativeSlot(placement)
        val typeConfig = nativeConfig(placement)
        val appCtx = context.applicationContext
        if (!canLoadNativeSlot(slotId, appCtx)) {
            postMain { listener?.onAdFailedToLoad(ERROR_AD_DISABLED_BY_CONFIG) }
            return
        }
        val resolvedIpKey = slotId
        val now = System.currentTimeMillis()
        val wrapper = nativeSlot.getPreloadedWrapper()
        if (isNativeWrapperReusable(wrapper, slotId, now) &&
            nativeSlot.hasValidPreloadedAd(now, AD_CACHE_TTL_MS)
        ) {
            nativeSlot.getAnyAd()?.let { cached ->
                postMain { listener?.onNativeAdLoaded(cached) }
                return
            }
        }
        if (nativeSlot.getState() == AdState.LOADING) {
            AdLogger.loadSkipped(slotId, "already_loading")
            postMain { listener?.onAdFailedToLoad("Already loading") }
            return
        }
        if (wrapper != null) {
            val age = now - wrapper.loadTime
            if (!isNativeWrapperReusable(wrapper, slotId, now)) {
                AdLogger.cacheExpired(slotId, wrapper.ipKey, age, AD_CACHE_TTL_MS)
            }
            nativeSlot.clear()
        }
        if (!canStartNativeLoad(slotId, typeConfig?.enabled == true, listener)) return
        nativeSlot.loadAd(
            context = appCtx,
            ipKey = resolvedIpKey,
            vpnLoadStatus = VpnState.DISCONNECTED,
            vpnSessionId = 0L,
            units = typeConfig!!.adUnits,
            listener = listener,
            onFinished = null
        )
    }

    fun getNativeAd(placement: NativePlacement): NativeAd? {
        val slotId = nativeSlotId(placement)
        val nativeSlot = nativeSlot(placement)
        if (!canShowNativeSlot(slotId)) return null
        val wrapper = nativeSlot.getPreloadedWrapper() ?: return null
        if (!isNativeWrapperReusable(wrapper, slotId)) {
            nativeSlot.clear()
            return null
        }
        return nativeSlot.acquireAdForDisplayAny()
    }

    fun isNativeAdLoaded(placement: NativePlacement): Boolean {
        val nativeSlot = nativeSlot(placement)
        return nativeSlot.isLoaded() &&
                isNativeWrapperReusable(nativeSlot.getPreloadedWrapper(), nativeSlotId(placement))
    }

    fun isNativeAdLoading(placement: NativePlacement): Boolean =
        nativeSlot(placement).getState() == AdState.LOADING

    fun destroyNativeAd(placement: NativePlacement) {
        nativeSlot(placement).destroy()
    }

    fun onNativePresented(context: Context, placement: NativePlacement) {
        val slotId = nativeSlotId(placement)
        NativeDailyCapStore.onPresented(
            appContext ?: context.applicationContext,
            slotId,
            config.nativeDailyLimit
        )
        val count = NativeDailyCapStore.getTodayCount(
            appContext ?: context.applicationContext,
            slotId
        )
        AdLogger.capPresentedCount(slotId, count, config.nativeDailyLimit)
        AdLogger.nativePresented(slotId, slotId)
    }

    fun onNativePlacementClosed(placement: NativePlacement) {
        nativeSlot(placement).destroyDisplayedAd()
    }

    /**
     * 同步广告位容器状态：达到频控时隐藏，未加载时展示骨架并触发加载，
     * 有缓存时绑定广告并立即预加载下一条。
     */
    fun updateNativeAdContainer(
        container: ViewGroup,
        placement: NativePlacement,
        loadWhenMissing: Boolean = true,
        preloadAfterDisplay: Boolean = true,
        forceRefresh: Boolean = false,
    ): NativeContainerState {
        val slotId = nativeSlotId(placement)
        val expectedTag = NATIVE_CONTAINER_TAG_PREFIX + slotId
        // 超限/禁用：广告与骨架占位一并隐藏
        if (!canShowNativeSlot(slotId)) {
            hideNativeContainer(container)
            return NativeContainerState.HIDDEN
        }
        // 非强制刷新时，已展示的广告继续保留，避免闪烁与重复计数
        if (!forceRefresh && container.tag == expectedTag && container.childCount > 0) {
            return NativeContainerState.DISPLAYED
        }

        val hideMedia = placement == NativePlacement.HOME
        val appCtx = container.context.applicationContext
        val slot = nativeSlot(placement)

        // 优先使用预加载的新广告（有缓存用缓存），成功后计数并预加载下一条
        val freshAd = getNativeAd(placement)
        if (freshAd != null) {
            bindNativeAd(container, freshAd, hideMedia = hideMedia)
            container.tag = expectedTag
            container.visibility = View.VISIBLE
            onNativePresented(container.context, placement)
            maybePreloadNextNative(appCtx, placement, preloadAfterDisplay)
            return NativeContainerState.DISPLAYED
        }

        // 强制刷新但尚无新广告时，保留已展示内容，避免骨架屏冲掉当前广告后被下次轮询再次计次
        if (container.tag == expectedTag && container.childCount > 0) {
            maybePreloadNextNative(appCtx, placement, preloadAfterDisplay)
            return NativeContainerState.DISPLAYED
        }

        // 无新广告时不复用已展示过的广告：同一个 NativeAd 重新绑定到新的 NativeAdView 会被 AdMob
        // 重复计一次曝光，而 OnPaidEventListener 对同一个广告只回调一次，导致曝光与收入口径失真。
        // 展示骨架并按需触发加载
        showNativeSkeleton(container)
        if (loadWhenMissing && !isNativeAdLoading(placement)) {
            val state = slot.getState()
            // NOT_LOADED / FAILED 可加载；调用方用较慢轮询避免失败后打爆
            if (state == AdState.NOT_LOADED || state == AdState.FAILED) {
                loadNativeAd(appCtx, placement, null)
            }
        }
        return NativeContainerState.LOADING
    }

    private fun maybePreloadNextNative(
        context: Context,
        placement: NativePlacement,
        preloadAfterDisplay: Boolean,
    ) {
        if (!preloadAfterDisplay) return
        if (isNativeAdLoaded(placement) || isNativeAdLoading(placement)) return
        loadNativeAd(context, placement, null)
    }

    fun releaseNativeAdContainer(container: ViewGroup, placement: NativePlacement) {
        container.removeAllViews()
        container.tag = null
        onNativePlacementClosed(placement)
    }

    fun bannerModeRegular(): BannerAdSlot.BannerMode = BannerAdSlot.BannerMode.REGULAR

    fun bannerModeCollapsible(): BannerAdSlot.BannerMode = BannerAdSlot.BannerMode.COLLAPSIBLE

    fun loadBanner(
        context: Context,
        mode: BannerAdSlot.BannerMode = BannerAdSlot.BannerMode.REGULAR,
        listener: AdLoadListener? = null,
        attachTo: ViewGroup? = null,
    ) {
        if (!canLoadBanner(context.applicationContext, mode, listener)) return
        if (attachTo == null && hasValidBannerCache(mode)) {
            AdLogger.interstitialCacheHit(bannerLabel(mode))
            postMain {
                listener?.onAdLoaded()
                dispatchBannerLoadObservers(mode, error = null)
            }
            return
        }
        // 已有同轨请求在飞：只入队一次性 listener，观察者由首次请求回调统一派发
        if (isBannerLoading(mode)) {
            if (listener != null) {
                bannerSlot.load(
                    context,
                    config.barBannerConfig?.adUnits.orEmpty(),
                    mode,
                    listener,
                    attachTo = null,
                )
            }
            return
        }
        bannerSlot.load(
            context,
            config.barBannerConfig?.adUnits.orEmpty(),
            mode,
            object : AdLoadListener {
                override fun onAdLoaded() {
                    if (attachTo != null &&
                        bannerSlot.isDisplayedIn(attachTo) &&
                        bannerSlot.isDisplayedMode(mode)
                    ) {
                        onBannerPresented(context, mode)
                    }
                    listener?.onAdLoaded()
                    dispatchBannerLoadObservers(mode, error = null)
                }

                override fun onAdFailedToLoad(error: String) {
                    listener?.onAdFailedToLoad(error)
                    dispatchBannerLoadObservers(mode, error)
                }
            },
            attachTo,
        )
    }

    fun hasValidBannerCache(mode: BannerAdSlot.BannerMode): Boolean =
        bannerSlot.hasValidCache(mode, System.currentTimeMillis(), AD_CACHE_TTL_MS)

    fun isBannerLoading(mode: BannerAdSlot.BannerMode): Boolean = bannerSlot.isLoading(mode)

    /**
     * 页面级加载状态监听：加载成功/失败时回调。
     * 普通轨：若已有有效缓存会 sticky 回调 onAdLoaded。
     * 折叠轨：不 sticky（离屏缓存不可展开，必须由页面就地加载）。
     * 页面销毁时必须调用 [removeBannerLoadObserver]。
     */
    fun addBannerLoadObserver(mode: BannerAdSlot.BannerMode, listener: AdLoadListener) {
        val list = bannerLoadObservers.getValue(mode)
        if (!list.contains(listener)) list.add(listener)
        if (mode == BannerAdSlot.BannerMode.REGULAR && hasValidBannerCache(mode)) {
            postMain { listener.onAdLoaded() }
        }
    }

    fun removeBannerLoadObserver(mode: BannerAdSlot.BannerMode, listener: AdLoadListener) {
        bannerLoadObservers.getValue(mode).remove(listener)
    }

    fun releaseBannerHost(container: ViewGroup) {
        bannerSlot.detachFrom(container)
    }

    /** 立即收起并销毁当前展示的 banner（离开 Browser 时用，避免展开层残留到 Home）。 */
    fun hideBanner(container: ViewGroup) {
        bannerSlot.hide(container)
    }

    fun isBannerDisplayedInMode(container: ViewGroup, mode: BannerAdSlot.BannerMode): Boolean =
        bannerSlot.isDisplayedIn(container) && bannerSlot.isDisplayedMode(mode)

    /** 丢弃指定轨缓存并取消在飞请求。 */
    fun discardBannerCache(mode: BannerAdSlot.BannerMode) {
        bannerSlot.discardCache(mode)
    }

    /**
     * 将对应轨的 bar_banner 挂到容器。
     * 普通轨可复用离屏缓存；折叠轨不应复用离屏缓存（AdMob 限制，见 discard + 就地加载）。
     * 展示完成后不预载下一条。
     */
    fun updateBannerContainer(
        container: ViewGroup,
        mode: BannerAdSlot.BannerMode,
    ): BannerContainerState {
        AdLogger.interstitialShowRequest(
            bannerLabel(mode),
            bannerSlot.getState(mode).name,
            if (hasValidBannerCache(mode)) bannerLabel(mode) else null,
        )
        if (!canShowBanner()) {
            AdLogger.interstitialShowBlocked(ERROR_AD_DISABLED_BY_CONFIG, bannerLabel(mode))
            bannerSlot.hide(container)
            return BannerContainerState.HIDDEN
        }
        if (bannerSlot.isDisplayedIn(container) && bannerSlot.isDisplayedMode(mode)) {
            return BannerContainerState.DISPLAYED
        }
        // 折叠轨离屏缓存挂上后不会展开，禁止复用
        if (mode == BannerAdSlot.BannerMode.COLLAPSIBLE) {
            return if (bannerSlot.isLoading(mode)) {
                BannerContainerState.LOADING
            } else {
                BannerContainerState.HIDDEN
            }
        }
        val fresh = bannerSlot.acquireForDisplay(mode, System.currentTimeMillis(), AD_CACHE_TTL_MS)
        if (fresh != null) {
            AdLogger.interstitialCacheHit(bannerLabel(mode))
            bannerSlot.attach(container, fresh)
            onBannerPresented(container.context, mode)
            return BannerContainerState.DISPLAYED
        }
        return if (bannerSlot.isLoading(mode)) {
            BannerContainerState.LOADING
        } else {
            BannerContainerState.HIDDEN
        }
    }

    enum class BannerContainerState {
        HIDDEN,
        LOADING,
        DISPLAYED,
    }

    private fun dispatchBannerLoadObservers(mode: BannerAdSlot.BannerMode, error: String?) {
        val observers = bannerLoadObservers.getValue(mode).toList()
        if (observers.isEmpty()) return
        postMain {
            observers.forEach { observer ->
                if (error == null) observer.onAdLoaded() else observer.onAdFailedToLoad(error)
            }
        }
    }

    private fun bannerLabel(mode: BannerAdSlot.BannerMode): String =
        if (mode == BannerAdSlot.BannerMode.COLLAPSIBLE) "$AD_TYPE_BANNER/collapsible" else "$AD_TYPE_BANNER/regular"

    private fun onBannerPresented(context: Context, mode: BannerAdSlot.BannerMode) {
        val ctx = appContext ?: context.applicationContext
        InterstitialDailyCapStore.onPresented(ctx, config.bannerDailyLimit, AD_TYPE_BANNER)
        AdLogger.nativePresented(bannerLabel(mode), AD_TYPE_BANNER)
        AdLogger.capPresentedCount(
            AD_TYPE_BANNER,
            InterstitialDailyCapStore.getTodayCount(ctx, AD_TYPE_BANNER),
            config.bannerDailyLimit,
        )
    }

    private fun canLoadBanner(
        context: Context,
        mode: BannerAdSlot.BannerMode,
        listener: AdLoadListener?,
    ): Boolean {
        if (!sdkInitialized) {
            AdLogger.loadBlocked(bannerLabel(mode), ERROR_SDK_NOT_INITIALIZED)
            postMain { listener?.onAdFailedToLoad(ERROR_SDK_NOT_INITIALIZED) }
            return false
        }
        if (!config.enabled || config.barBannerConfig?.enabled != true) {
            AdLogger.loadBlocked(bannerLabel(mode), ERROR_AD_DISABLED_BY_CONFIG)
            postMain { listener?.onAdFailedToLoad(ERROR_AD_DISABLED_BY_CONFIG) }
            return false
        }
        if (!canLoadOrShowBanner(context)) {
            val count = InterstitialDailyCapStore.getTodayCount(context, AD_TYPE_BANNER)
            AdLogger.capBlocked(bannerLabel(mode), "加载", count, config.bannerDailyLimit)
            postMain { listener?.onAdFailedToLoad(ERROR_BANNER_DAILY_LIMIT) }
            return false
        }
        return true
    }

    private fun canShowBanner(): Boolean {
        val ctx = appContext ?: return false
        if (!appForeground) return false
        if (!config.enabled || config.barBannerConfig?.enabled != true) return false
        if (!canLoadOrShowBanner(ctx)) {
            val count = InterstitialDailyCapStore.getTodayCount(ctx, AD_TYPE_BANNER)
            AdLogger.capBlocked(AD_TYPE_BANNER, "展示", count, config.bannerDailyLimit)
            return false
        }
        return true
    }

    private fun canLoadOrShowBanner(context: Context): Boolean =
        InterstitialDailyCapStore.canLoadOrShow(context, config.bannerDailyLimit, AD_TYPE_BANNER)

    fun preloadNativeAd(
        context: Context,
        placement: NativePlacement,
        delayMs: Long = 0L,
    ) {
        val appCtx = context.applicationContext
        val loadAction = Runnable {
            refreshConfig()
            if (!isNativeAdLoaded(placement) && !isNativeAdLoading(placement)) {
                loadNativeAd(appCtx, placement, null)
            }
        }
        if (delayMs > 0L) {
            mainHandler.postDelayed(loadAction, delayMs)
        } else {
            mainHandler.post(loadAction)
        }
    }

    private fun showNativeSkeleton(container: ViewGroup) {
        if (container.tag == R.layout.item_result_native_ad_skeleton && container.childCount > 0) {
            return
        }
        container.removeAllViews()
        LayoutInflater.from(container.context)
            .inflate(R.layout.item_result_native_ad_skeleton, container, true)
        container.tag = R.layout.item_result_native_ad_skeleton
        container.visibility = View.VISIBLE
    }

    private fun hideNativeContainer(container: ViewGroup) {
        container.removeAllViews()
        container.tag = null
        container.visibility = View.GONE
    }

    private fun bindNativeAd(container: ViewGroup, nativeAd: NativeAd, hideMedia: Boolean) {
        val binding = ItemResultNativeAdBinding.inflate(
            LayoutInflater.from(container.context),
            container,
            false,
        )
        val adView = binding.root
        binding.adHeadline.text = nativeAd.headline
        binding.adBody.text = nativeAd.body
        binding.adBody.visibility = if (nativeAd.body.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.adAdvertiser.text = nativeAd.advertiser
        binding.adAdvertiser.visibility =
            if (nativeAd.advertiser.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.adCallToAction.text = nativeAd.callToAction
        binding.adCallToAction.visibility =
            if (nativeAd.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.adAppIcon.setImageDrawable(nativeAd.icon?.drawable)
        binding.adAppIcon.visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE

        // 首页 home_native 隐藏媒体区，仅保留非媒体内容；其它广告位正常展示媒体。
        if (hideMedia) {
            binding.adMedia.visibility = View.GONE
        } else {
            binding.adMedia.visibility = View.VISIBLE
            binding.adMedia.mediaContent = nativeAd.mediaContent
            adView.mediaView = binding.adMedia
        }

        adView.headlineView = binding.adHeadline
        adView.bodyView = binding.adBody
        adView.advertiserView = binding.adAdvertiser
        adView.callToActionView = binding.adCallToAction
        adView.iconView = binding.adAppIcon
        adView.adChoicesView = binding.adChoices
        adView.setNativeAd(nativeAd)

        container.removeAllViews()
        container.addView(adView)
    }

    enum class NativeContainerState {
        HIDDEN,
        LOADING,
        DISPLAYED,
    }

    fun loadNativeAdForResult(context: Context, _ipKey: String, listener: NativeAdLoadListener?) =
        loadNativeAd(context, NativePlacement.CONNECT, listener)

    fun isNativeAdLoadedForResult(ipKey: String): Boolean =
        isNativeAdLoaded(NativePlacement.CONNECT)



    private fun nativeSlotId(placement: NativePlacement): String = when (placement) {
        NativePlacement.HOME -> AD_TYPE_NATIVE_HOME
        NativePlacement.CONNECT -> AD_TYPE_NATIVE_COMMON
    }

    private fun nativeSlot(placement: NativePlacement): NativeAdSlot = when (placement) {
        NativePlacement.HOME -> nativeHomeSlot
        NativePlacement.CONNECT -> nativeResultSlot
    }

    private fun nativeConfig(placement: NativePlacement): AdTypeConfig? = when (placement) {
        NativePlacement.HOME -> config.homeNativeConfig
        NativePlacement.CONNECT -> config.nativeCommonConfig
    }

    fun preloadIntersConnectOnVpnConnected(context: Context, ipKey: String) {
        if (!isAdEnabled()) {
            AdLogger.preloadConnectedSkip("ads disabled or sdk not initialized")
            return
        }
        if (config.intersConnectConfig?.enabled == true) {
            preloadInterstitialWithRetry(context.applicationContext, ipKey, 0)
        } else {
            AdLogger.preloadConnectedSlot("inters_connect", false, "skip")
        }
    }

    private fun preloadInterstitialWithRetry(context: Context, ipKey: String, retry: Int) {
        when {
            isInterstitialAdLoadedForIp(ipKey) -> {
                AdLogger.preloadConnectedSlot("inters_connect", true, "skip(cached)")
                return
            }

            interstitialAdState == AdState.LOADING -> {
                AdLogger.preloadConnectedSlot("inters_connect", true, "skip(in_progress)")
                return
            }

            else -> AdLogger.preloadConnectedSlot("inters_connect", true, "load")
        }
        loadInterstitialAd(context, ipKey, object : AdLoadListener {
            override fun onAdLoaded() = Unit

            override fun onAdFailedToLoad(error: String) {
                if (shouldRetryPreload(error, retry)) {
                    val next = retry + 1
                    AdLogger.preloadConnectedRetry("inters_connect", next, error)
                    mainHandler.postDelayed(
                        { preloadInterstitialWithRetry(context, ipKey, next) },
                        PRELOAD_RETRY_DELAY_MS
                    )
                }
            }
        })
    }

    private fun preloadOpenAdWithRetry(context: Context, ipKey: String, retry: Int) {
        when {
            isOpenAdLoaded() -> {
                AdLogger.preloadConnectedSlot(AD_TYPE_OPEN, true, "skip(cached)")
                return
            }

            openAdState == AdState.LOADING -> {
                AdLogger.preloadConnectedSlot(AD_TYPE_OPEN, true, "skip(in_progress)")
                return
            }

            else -> AdLogger.preloadConnectedSlot(AD_TYPE_OPEN, true, "load")
        }
        loadOpenAd(context, object : AdLoadListener {
            override fun onAdLoaded() = Unit

            override fun onAdFailedToLoad(error: String) {
                if (shouldRetryPreload(error, retry)) {
                    val next = retry + 1
                    AdLogger.preloadConnectedRetry(AD_TYPE_OPEN, next, error)
                    mainHandler.postDelayed(
                        { preloadOpenAdWithRetry(context, ipKey, next) },
                        PRELOAD_RETRY_DELAY_MS
                    )
                }
            }
        })
    }

    private fun preloadNativeResultWithRetry(context: Context, ipKey: String, retry: Int) {
        when {
            isNativeAdLoadedForResult(ipKey) -> {
                AdLogger.preloadConnectedSlot(AD_TYPE_NATIVE_COMMON, true, "skip(cached)")
                return
            }

            nativeResultSlot.getState() == AdState.LOADING -> {
                AdLogger.preloadConnectedSlot(AD_TYPE_NATIVE_COMMON, true, "skip(in_progress)")
                return
            }

            else -> AdLogger.preloadConnectedSlot(AD_TYPE_NATIVE_COMMON, true, "load")
        }
        loadNativeAdForResult(context, ipKey, object : NativeAdLoadListener {
            override fun onNativeAdLoaded(nativeAd: NativeAd) = Unit

            override fun onAdFailedToLoad(error: String) {
                if (shouldRetryPreload(error, retry)) {
                    val next = retry + 1
                    AdLogger.preloadConnectedRetry(AD_TYPE_NATIVE_COMMON, next, error)
                    mainHandler.postDelayed(
                        { preloadNativeResultWithRetry(context, ipKey, next) },
                        PRELOAD_RETRY_DELAY_MS
                    )
                }
            }
        })
    }

    private fun shouldRetryPreload(error: String, retry: Int): Boolean {
        if (retry >= PRELOAD_MAX_RETRY) return false
        return error.contains("Already loading", ignoreCase = true)
    }

    private fun canLoadOrShowInterstitial(context: Context): Boolean {
        return InterstitialDailyCapStore.canLoadOrShow(context, config.interstitialDailyLimit)
    }

    private fun canLoadOrShowOpenAd(context: Context): Boolean {
        return InterstitialDailyCapStore.canLoadOrShow(
            context,
            config.openDailyLimit,
            AD_TYPE_OPEN,
        )
    }

    private fun canLoadNativeSlot(slot: String, context: Context): Boolean {
        val typeConfig = when (slot) {
            AD_TYPE_NATIVE_COMMON -> config.nativeCommonConfig
            AD_TYPE_NATIVE_HOME -> config.homeNativeConfig
            else -> null
        }
        if (!config.enabled || typeConfig?.enabled != true) return false
        val allowed = NativeDailyCapStore.canLoadOrShow(context, slot, config.nativeDailyLimit)
        if (!allowed) {
            val count = NativeDailyCapStore.getTodayCount(context, slot)
            AdLogger.capBlocked(slot, "加载", count, config.nativeDailyLimit)
        }
        return allowed
    }

    private fun canShowNativeSlot(slot: String): Boolean {
        val ctx = appContext ?: return false
        val typeConfig = when (slot) {
            AD_TYPE_NATIVE_COMMON -> config.nativeCommonConfig
            AD_TYPE_NATIVE_HOME -> config.homeNativeConfig
            else -> null
        }
        if (!appForeground) return false
        if (!config.enabled || typeConfig?.enabled != true) return false
        val allowed = NativeDailyCapStore.canLoadOrShow(ctx, slot, config.nativeDailyLimit)
        if (!allowed) {
            val count = NativeDailyCapStore.getTodayCount(ctx, slot)
            AdLogger.capBlocked(slot, "展示", count, config.nativeDailyLimit)
        }
        return allowed
    }

    private fun canStartNativeLoad(
        adType: String,
        typeEnabled: Boolean,
        listener: NativeAdLoadListener?,
    ): Boolean {
        if (!sdkInitialized) {
            AdLogger.loadBlocked(adType, ERROR_SDK_NOT_INITIALIZED)
            postMain { listener?.onAdFailedToLoad(ERROR_SDK_NOT_INITIALIZED) }
            return false
        }
        if (!config.enabled || !typeEnabled) {
            AdLogger.loadBlocked(adType, ERROR_AD_DISABLED_BY_CONFIG)
            postMain { listener?.onAdFailedToLoad(ERROR_AD_DISABLED_BY_CONFIG) }
            return false
        }
        return true
    }

    private fun parseConfig(json: String): AdConfig {
        if (json.isBlank()) return defaultConfig()
        return runCatching { AdConfigSerializer.fromJson(json) }
            .onFailure { AdLogger.configParseFailed(it) }
            .getOrDefault(defaultConfig())
    }

    private fun loadInterstitialWaterfall(
        context: Context,
        units: List<AdUnit>,
        ipKey: String,
        vpnSessionId: Long,
        token: Long,
        listener: AdLoadListener?
    ) {
        if (interstitialCurrentIndex >= units.size) {
            interstitialAdState = AdState.FAILED
            interstitialCache.clear()
            AdLogger.interstitialAllFailed(ipKey)
            postMain { listener?.onAdFailedToLoad(ERROR_ALL_INTERSTITIAL_FAILED) }
            return
        }
        val unit = units[interstitialCurrentIndex++]
        val adUnitId = resolveAdId(AdKind.INTERSTITIAL, unit.id)
        interstitialPosId = unit.name
        interstitialRequestStartMs = System.currentTimeMillis()
        AdLogger.interstitialWaterfallTry(interstitialCurrentIndex - 1, unit.name, adUnitId, ipKey)

        InterstitialAd.load(
            context, adUnitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    if (token != interstitialLoadToken) return

                    // 设置广告收益监听
                    ad.setOnPaidEventListener { adValue ->
                        AdTrackingHelper.trackAdRevenueAdjust(adValue, ad.responseInfo, ad.adUnitId)
                        runCatching {
                            AdTrackingHelper.trackAdImpressionRevenueMicros(
                                context,
                                adValue,
                                AdTrackingHelper.AdType.INTERSTITIAL,
                                unit.name,
                            )
                        }.onFailure {
                            AdLogger.loadBlocked(
                                AD_TYPE_INTERSTITIAL,
                                "paid value upload failed: ${it.message}"
                            )
                        }
                    }
                    interstitialCache.put(
                        ad = ad,
                        slotId = AD_TYPE_INTERSTITIAL,
                        ipKey = ipKey,
                        loadTime = System.currentTimeMillis(),
                        vpnLoadStatus = VpnState.CONNECTED,
                        vpnSessionId = vpnSessionId
                    )
                    interstitialAdState = AdState.LOADED
                    AdLogger.interstitialLoaded(unit.name, adUnitId, ipKey)
                    postMain { listener?.onAdLoaded() }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdLogger.interstitialLoadFailed(unit.name, ipKey, error.message)
                    loadInterstitialWaterfall(context, units, ipKey, vpnSessionId, token, listener)
                }
            })
    }

    private fun loadPlacementWaterfall(
        context: Context,
        placement: InterstitialPlacement,
        units: List<AdUnit>,
        index: Int,
        listener: AdLoadListener?,
    ) {
        val slot = placementSlots.getValue(placement)
        if (index >= units.size) {
            slot.ad = null; slot.requestId = null; slot.posId = null; slot.loading =
                false; slot.state = AdState.FAILED
            postMain { listener?.onAdFailedToLoad(ERROR_ALL_INTERSTITIAL_FAILED) }
            return
        }
        val unit = units[index]
        AdLogger.interstitialWaterfallTry(index, unit.name, unit.id, placement.id)
        InterstitialAd.load(
            context,
            unit.id,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    ad.setOnPaidEventListener { adValue ->
                        AdTrackingHelper.trackAdRevenueAdjust(adValue, ad.responseInfo, ad.adUnitId)
                        runCatching {
                            AdTrackingHelper.trackAdImpressionRevenueMicros(
                                context,
                                adValue,
                                AdTrackingHelper.AdType.INTERSTITIAL,
                                unit.name
                            )
                        }.onFailure {
                            AdLogger.loadBlocked(
                                placement.id,
                                "paid value upload failed: ${it.message}"
                            )
                        }
                    }
                    slot.ad = ad
                    slot.posId = unit.name
                    slot.loadedAt = System.currentTimeMillis();
                    slot.loading = false
                    slot.state = AdState.LOADED
                    AdLogger.interstitialLoaded(unit.name, unit.id, placement.id)
                    postMain { listener?.onAdLoaded() }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdLogger.interstitialLoadFailed(unit.name, placement.id, error.message)
                    loadPlacementWaterfall(context, placement, units, index + 1, listener)
                }
            })
    }

    private fun canLoadPlacement(
        context: Context,
        placement: InterstitialPlacement,
        type: AdTypeConfig?,
        listener: AdLoadListener?,
    ): Boolean {
        val limit =
            if (placement.usesOpenCap) config.openDailyLimit else config.interstitialDailyLimit
        val capSlot = if (placement.usesOpenCap) AD_TYPE_OPEN else "interstitial"
        if (!sdkInitialized || !config.enabled || type?.enabled != true || type.adUnits.isEmpty()) {
            AdLogger.loadBlocked(
                placement.id,
                "sdkInitialized=$sdkInitialized configEnabled=${config.enabled} placementEnabled=${type?.enabled} units=${type?.adUnits?.size ?: 0}",
            )
            postMain { listener?.onAdFailedToLoad(ERROR_AD_DISABLED_BY_CONFIG) }
            return false
        }
        if (!InterstitialDailyCapStore.canLoadOrShow(context, limit, capSlot)) {
            AdLogger.capBlocked(
                placement.id,
                "load",
                InterstitialDailyCapStore.getTodayCount(context, capSlot),
                limit
            )
            postMain { listener?.onAdFailedToLoad(ERROR_INTERSTITIAL_DAILY_LIMIT) }
            return false
        }
        return true
    }

    private fun loadOpenAdWaterfall(
        context: Context,
        units: List<AdUnit>,
        ipKey: String,
        vpnSessionId: Long,
        token: Long,
        listener: AdLoadListener?
    ) {
        if (openAdCurrentIndex >= units.size) {
            openAdState = AdState.FAILED
            openAdCache.clear()
            openAdShownIpKey = null
            postMain { listener?.onAdFailedToLoad(ERROR_ALL_INTERSTITIAL_FAILED) }
            return
        }
        val unit = units[openAdCurrentIndex++]
        val adUnitId = resolveAdId(AdKind.APP_OPEN, unit.id)
        openAdPosId = unit.name
        openAdRequestStartMs = System.currentTimeMillis()
        AdLogger.interstitialWaterfallTry(
            openAdCurrentIndex - 1,
            unit.name,
            adUnitId,
            ipKey
        )

        AppOpenAd.load(
            context, adUnitId, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    if (token != openAdLoadToken) return

                    ad.setOnPaidEventListener { adValue ->
                        AdTrackingHelper.trackAdRevenueAdjust(
                            adValue,
                            ad.responseInfo,
                            ad.adUnitId
                        )
                        runCatching {
                            AdTrackingHelper.trackAdImpressionRevenueMicros(
                                context,
                                adValue,
                                AdTrackingHelper.AdType.OPEN,
                                unit.name
                            )
                        }.onFailure {
                            AdLogger.loadBlocked(
                                AD_TYPE_OPEN,
                                "paid value upload failed: ${it.message}"
                            )
                        }
                    }
                    openAdCache.put(
                        ad = ad,
                        slotId = AD_TYPE_OPEN,
                        ipKey = ipKey,
                        loadTime = System.currentTimeMillis(),
                        vpnLoadStatus = VpnState.DISCONNECTED,
                        vpnSessionId = vpnSessionId
                    )
                    openAdState = AdState.LOADED
                    AdLogger.interstitialLoaded(unit.name, adUnitId, ipKey)
                    postMain { listener?.onAdLoaded() }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdLogger.interstitialLoadFailed(unit.name, ipKey, error.message)
                    loadOpenAdWaterfall(
                        context,
                        units,
                        ipKey,
                        vpnSessionId,
                        token,
                        listener
                    )
                }
            })
    }


    private fun canStartLoad(
        adType: String,
        typeEnabled: Boolean,
        listener: AdLoadListener?
    ): Boolean {
        if (!sdkInitialized) {
            AdLogger.loadBlocked(adType, ERROR_SDK_NOT_INITIALIZED)
            postMain { listener?.onAdFailedToLoad(ERROR_SDK_NOT_INITIALIZED) }
            return false
        }
        if (!isConnectedStableForAds()) {
            AdLogger.loadBlocked(adType, "VPN is not connected")
            postMain { listener?.onAdFailedToLoad("VPN is not connected") }
            return false
        }
        if (!config.enabled || !typeEnabled) {
            val reason =
                "Ad config disabled | globalEnabled=${config.enabled} typeEnabled=$typeEnabled"
            AdLogger.loadBlocked(adType, reason)
            postMain { listener?.onAdFailedToLoad(ERROR_AD_DISABLED_BY_CONFIG) }
            return false
        }
        return true
    }

    private fun AdConfig.placementConfig(placement: InterstitialPlacement): AdTypeConfig? =
        when (placement) {
            InterstitialPlacement.GUIDE_1 -> intersGuide1Config
            InterstitialPlacement.GUIDE_2 -> intersGuide2Config
            InterstitialPlacement.HOME -> intersHomeConfig
            InterstitialPlacement.BACK -> intersBrowserBackConfig
            InterstitialPlacement.SEARCH_BACK -> searchIntersBackConfig
        }

    private fun isOpenAdWrapperReusable(
        wrapper: AdWrapper<*>?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        wrapper ?: return false
        return wrapper.slotId == AD_TYPE_OPEN && now - wrapper.loadTime < AD_CACHE_TTL_MS
    }

    private fun canStartOpenAdLoad(typeEnabled: Boolean, listener: AdLoadListener?): Boolean {
        if (!sdkInitialized) {
            postMain { listener?.onAdFailedToLoad(ERROR_SDK_NOT_INITIALIZED) }
            return false
        }
        if (!config.enabled || !typeEnabled) {
            postMain { listener?.onAdFailedToLoad(ERROR_AD_DISABLED_BY_CONFIG) }
            return false
        }
        return true
    }

    private fun canShowOpenAd(
        activity: Activity,
        loaded: Boolean,
        listener: AdShowListener?
    ): Boolean {
        if (!sdkInitialized || !config.enabled || !appForeground || isAnyAdShowing() || !loaded ||
            activity.isFinishing || activity.isDestroyed
        ) {
            postMain { listener?.onAdShowFailed(ERROR_AD_NOT_LOADED) }
            return false
        }
        return true
    }

    private fun canShowCommon(
        adType: String,
        activity: Activity,
        loaded: Boolean,
        listener: AdShowListener?
    ): Boolean {
        if (!sdkInitialized || !config.enabled) {
            val reason = "SDK or config disabled"
            AdLogger.interstitialShowBlocked(reason, null)
            postMain { listener?.onAdShowFailed("Ad is disabled or SDK not initialized") }
            return false
        }
        if (!isConnectedStableForAds()) {
            AdLogger.interstitialShowBlocked("VPN is not connected", null)
            postMain { listener?.onAdShowFailed("VPN is not connected") }
            return false
        }
        if (!appForeground) {
            AdLogger.interstitialShowBlocked(ERROR_APP_IN_BACKGROUND, null)
            postMain { listener?.onAdShowFailed(ERROR_APP_IN_BACKGROUND) }
            return false
        }
        if (isAnyAdShowing()) {
            AdLogger.interstitialShowBlocked(ERROR_ANOTHER_AD_SHOWING, null)
            postMain { listener?.onAdShowFailed(ERROR_ANOTHER_AD_SHOWING) }
            return false
        }
        if (!loaded) {
            AdLogger.interstitialShowBlocked(ERROR_AD_NOT_LOADED, null)
            postMain { listener?.onAdShowFailed(ERROR_AD_NOT_LOADED) }
            return false
        }
        if (activity.isFinishing || activity.isDestroyed) {
            AdLogger.interstitialShowBlocked(ERROR_INVALID_ACTIVITY, null)
            postMain { listener?.onAdShowFailed(ERROR_INVALID_ACTIVITY) }
            return false
        }
        return true
    }


    private fun createFullscreenCallback(
        onShow: () -> Unit,
        onClick: () -> Unit,
        onDismiss: () -> Unit,
        onFail: (String) -> Unit
    ): FullScreenContentCallback {
        return object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() = onShow()
            override fun onAdClicked() = onClick()
            override fun onAdDismissedFullScreenContent() = onDismiss()
            override fun onAdFailedToShowFullScreenContent(adError: AdError) =
                onFail(adError.message)
        }
    }

    private fun resolveAdId(kind: AdKind, prodId: String): String {
        return prodId
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun dispatchSdkInitializationResult(success: Boolean) {
        val callbacks = sdkInitializationCallbacks.toList()
        sdkInitializationCallbacks.clear()
        postMain { callbacks.forEach { it(success) } }
    }

    private fun mapAdType(adType: String): String {
        return when (adType) {
            AD_TYPE_INTERSTITIAL,
            AD_TYPE_OPEN -> "open"

            AD_TYPE_NATIVE_COMMON, AD_TYPE_NATIVE_HOME -> "native"
            else -> adType
        }
    }

    private fun defaultConfig(): AdConfig {
        return AdConfig(
            enabled = false,
            adsDebugMode = false,
            nativeDailyLimit = 0,
            interstitialDailyLimit = 0,
            openDailyLimit = 0,
            bannerDailyLimit = 0,
            intersConnectLoadTime = 10000,
            intersOpenLoadTime = 10000,
            intersConnectConfig = AdTypeConfig(enabled = false),
            intersBackConfig = AdTypeConfig(enabled = false),
            nativeCommonConfig = AdTypeConfig(enabled = false),
            homeNativeConfig = AdTypeConfig(enabled = false),
            intersGuide1Config = AdTypeConfig(enabled = false),
            intersGuide2Config = AdTypeConfig(enabled = false),
            intersHomeConfig = AdTypeConfig(enabled = false),
            intersBrowserBackConfig = AdTypeConfig(enabled = false),
            searchIntersBackConfig = AdTypeConfig(enabled = false),
            barBannerConfig = AdTypeConfig(enabled = false),
        )
    }

    private fun AdTypeConfig?.isEquivalent(other: AdTypeConfig?): Boolean = this == other

    private enum class AdKind { APP_OPEN, INTERSTITIAL, NATIVE }
}
