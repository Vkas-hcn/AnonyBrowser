package com.anony.bro.wser.ads

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.anony.bro.wser.ads.AdState
import com.anony.bro.wser.ads.AdUnit
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.anony.bro.wser.data.ref.AdTrackingHelper

/**
 * bar_banner 双轨缓存：
 *  - [BannerMode.REGULAR] 普通 Banner（不带 collapsible），HomeFragment 使用；
 *  - [BannerMode.COLLAPSIBLE] 可展开 Banner（collapsible=bottom），BrowserFragment 使用。
 *
 * 两轨各自独立加载/缓存，可并行；[displayed] 为当前挂载的实例（底部只有一个 overlay）。
 * 开关/频控/埋点仍走同一个 [slotName]（bar_banner）。
 */
class BannerAdSlot(
    private val slotName: String,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    enum class BannerMode { REGULAR, COLLAPSIBLE }

    data class Record(
        val adView: AdView,
        val unitId: String,
        val loadTime: Long,
        val collapsible: Boolean,
        var showStartMs: Long = 0L,
    )

    private inner class Track(val mode: BannerMode) {
        @Volatile var adState = AdState.NOT_LOADED
        @Volatile var loadToken = 0L
        @Volatile var currentIndex = 0
        @Volatile var currentRequestStartMs = 0L
        var cached: Record? = null
        var attachedLoadingAdView: AdView? = null
        private val pendingListeners = ArrayList<AdLoadListener>()

        fun isLoading(): Boolean = adState == AdState.LOADING

        fun hasValidCache(nowMs: Long, ttlMs: Long): Boolean {
            val record = cached ?: return false
            return adState == AdState.LOADED && nowMs - record.loadTime < ttlMs
        }

        fun addListener(listener: AdLoadListener?) {
            if (listener == null) return
            synchronized(pendingListeners) { pendingListeners.add(listener) }
        }

        fun takeListeners(): List<AdLoadListener> {
            synchronized(pendingListeners) {
                if (pendingListeners.isEmpty()) return emptyList()
                return pendingListeners.toList().also { pendingListeners.clear() }
            }
        }
    }

    private val regularTrack = Track(BannerMode.REGULAR)
    private val collapsibleTrack = Track(BannerMode.COLLAPSIBLE)

    private var displayed: Record? = null

    private fun track(mode: BannerMode) =
        if (mode == BannerMode.COLLAPSIBLE) collapsibleTrack else regularTrack

    private fun label(mode: BannerMode) =
        if (mode == BannerMode.COLLAPSIBLE) "$slotName/collapsible" else "$slotName/regular"

    fun getState(mode: BannerMode): AdState = track(mode).adState

    fun isLoading(mode: BannerMode): Boolean = track(mode).isLoading()

    fun hasValidCache(mode: BannerMode, nowMs: Long, ttlMs: Long): Boolean =
        track(mode).hasValidCache(nowMs, ttlMs)

    fun getDisplayed(): Record? = displayed

    fun isDisplayedIn(container: ViewGroup): Boolean {
        val adView = displayed?.adView ?: return false
        return adView.parent === container
    }

    private fun isDisplayedCollapsible(): Boolean {
        val record = displayed ?: return false
        return record.collapsible || record.adView.isCollapsible
    }

    /** 当前展示实例是否与目标轨匹配（避免重复计数、避免把展开条当普通条复用）。 */
    fun isDisplayedMode(mode: BannerMode): Boolean {
        if (displayed == null) return false
        return if (mode == BannerMode.COLLAPSIBLE) isDisplayedCollapsible() else !isDisplayedCollapsible()
    }

    fun acquireForDisplay(mode: BannerMode, nowMs: Long, ttlMs: Long): Record? {
        val t = track(mode)
        val record = t.cached ?: return null
        if (nowMs - record.loadTime >= ttlMs) {
            AdLogger.cacheExpired(label(mode), null, nowMs - record.loadTime, ttlMs)
            destroyRecord(record)
            t.cached = null
            t.adState = AdState.NOT_LOADED
            return null
        }
        t.cached = null
        t.adState = AdState.NOT_LOADED
        return record
    }

    fun attach(container: ViewGroup, record: Record) {
        if (displayed !== record) {
            closeDisplayed()
        }
        (record.adView.parent as? ViewGroup)?.removeView(record.adView)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        container.addView(
            record.adView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (record.showStartMs == 0L) {
            record.showStartMs = System.currentTimeMillis()
        }
        displayed = record
        AdLogger.bannerCollapsible(slotName, record.collapsible || record.adView.isCollapsible)
    }

    fun detachFrom(container: ViewGroup) {
        val adView = displayed?.adView ?: return
        if (adView.parent === container) {
            container.removeView(adView)
        }
        if (container.childCount == 0) {
            container.visibility = View.GONE
        }
    }

    fun hide(container: ViewGroup) {
        closeDisplayed()
        container.removeAllViews()
        container.visibility = View.GONE
    }

    /** 丢弃指定轨的缓存并取消进行中的加载（用于折叠轨离屏缓存不可展开时强制就地重载）。 */
    fun discardCache(mode: BannerMode) {
        val t = track(mode)
        t.loadToken++
        destroyRecord(t.cached)
        destroyAdView(t.attachedLoadingAdView)
        t.cached = null
        t.attachedLoadingAdView = null
        t.adState = AdState.NOT_LOADED
        t.takeListeners()
        if (isDisplayedMode(mode)) {
            closeDisplayed()
        }
        AdLogger.loadSkipped(label(mode), "cache_discarded")
    }

    fun load(
        context: Context,
        units: List<AdUnit>,
        mode: BannerMode,
        listener: AdLoadListener?,
        attachTo: ViewGroup? = null,
    ) {
        val t = track(mode)
        AdLogger.nativeLoadRequest(label(mode), slotName, t.adState.toString())
        t.addListener(listener)
        if (units.isEmpty()) {
            t.adState = AdState.FAILED
            AdLogger.nativeAllFailed(label(mode), slotName)
            val listeners = t.takeListeners()
            postMain { listeners.forEach { it.onAdFailedToLoad(ERROR_NO_UNITS) } }
            return
        }
        if (t.isLoading()) {
            AdLogger.loadSkipped(label(mode), "already_loading")
            return
        }
        val token = ++t.loadToken
        t.adState = AdState.LOADING
        t.currentIndex = 0
        postMain { loadWaterfall(context, units, mode, token, attachTo) }
    }

    fun clear() {
        clearTrack(regularTrack)
        clearTrack(collapsibleTrack)
        closeDisplayed()
    }

    private fun clearTrack(t: Track) {
        t.loadToken++
        destroyRecord(t.cached)
        t.cached = null
        t.adState = AdState.NOT_LOADED
        t.takeListeners()
    }

    private fun loadWaterfall(
        context: Context,
        units: List<AdUnit>,
        mode: BannerMode,
        token: Long,
        attachTo: ViewGroup?,
    ) {
        val t = track(mode)
        if (token != t.loadToken) return
        if (t.currentIndex >= units.size) {
            t.adState = AdState.FAILED
            AdLogger.nativeAllFailed(label(mode), slotName)
            if (attachTo != null) {
                // 就地加载整轮失败：清空并收起容器，避免留下可见空壳（空白条）。
                closeDisplayed()
                attachTo.removeAllViews()
                attachTo.visibility = View.GONE
            }
            val listeners = t.takeListeners()
            postMain { listeners.forEach { it.onAdFailedToLoad(ERROR_ALL_FAILED) } }
            return
        }
        val unit = units[t.currentIndex++]

        t.currentRequestStartMs = System.currentTimeMillis()
        AdLogger.nativeWaterfallTry(label(mode), t.currentIndex - 1, unit.name, unit.id, slotName)
        val adContext = if (context is Activity && !context.isDestroyed) context else context.applicationContext
        val adView = AdView(adContext).apply {
            adUnitId = unit.id
            setAdSize(currentBannerSize(adContext))
        }
        if (attachTo != null) {
            (adView.parent as? ViewGroup)?.removeView(adView)
            attachTo.removeAllViews()
            attachTo.visibility = View.VISIBLE
            attachTo.addView(
                adView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            t.attachedLoadingAdView = adView
        }
        adView.setOnPaidEventListener(OnPaidEventListener { adValue: AdValue ->
            AdTrackingHelper.trackAdRevenueAdjust(adValue, adView.responseInfo, unit.id)
            runCatching {
                AdTrackingHelper.trackAdImpressionRevenueMicros(
                    context.applicationContext,
                    adValue,
                    AdTrackingHelper.AdType.BANNER,
                    slotName
                )
            }
        })
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                // AdMob 自动刷新会再次回调同一 AdView；已在展示/缓存则只记日志，勿当新一次瀑布流。
                if (displayed?.adView === adView || t.cached?.adView === adView) {
                    AdLogger.bannerCollapsible(label(mode), adView.isCollapsible)
                    AdLogger.nativeLoaded(label(mode), unit.name, unit.id, slotName)
                    return
                }
                if (token != t.loadToken) {
                    if (t.attachedLoadingAdView === adView) t.attachedLoadingAdView = null
                    if (displayed?.adView !== adView && t.cached?.adView !== adView) {
                        destroyAdView(adView)
                    }
                    return
                }
                if (t.attachedLoadingAdView === adView) t.attachedLoadingAdView = null
                val collapsible = adView.isCollapsible
                AdLogger.bannerCollapsible(label(mode), collapsible)
                // 非 collapsible 填充也照常展示（AdMob 允许对 collapsible 请求返回普通条），
                // 展示折叠态好过留白；能否展开取决于 SDK 与是否就地可见加载。
                AdLogger.nativeLoaded(label(mode), unit.name, unit.id, slotName)
                val record = Record(
                    adView = adView,
                    unitId = unit.id,
                    loadTime = System.currentTimeMillis(),
                    collapsible = collapsible,
                )
                if (attachTo != null && adView.parent === attachTo) {
                    closeDisplayed()
                    record.showStartMs = System.currentTimeMillis()
                    displayed = record
                    if (t.cached?.adView === adView) t.cached = null
                } else {
                    destroyRecord(t.cached)
                    t.cached = record
                }
                t.adState = AdState.LOADED
                val listeners = t.takeListeners()
                postMain { listeners.forEach { it.onAdLoaded() } }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                if (t.attachedLoadingAdView === adView) t.attachedLoadingAdView = null
                // 自动刷新失败：保留当前已展示/已缓存的广告，禁止 destroy + 瀑布流重试。
                if (displayed?.adView === adView || t.cached?.adView === adView) {
                    AdLogger.nativeLoadFailed(
                        label(mode),
                        unit.name,
                        slotName,
                        "refresh_failed: ${error.message} (code=${error.code})",
                    )
                    return
                }
                if (adView.parent === attachTo) {
                    attachTo?.removeView(adView)
                    if (attachTo?.childCount == 0) attachTo.visibility = View.GONE
                }
                if (adView.parent != null) adView.destroy()
                if (token != t.loadToken) return
                AdLogger.nativeLoadFailed(label(mode), unit.name, slotName, "${error.message} (code=${error.code})")

                loadWaterfall(context, units, mode, token, attachTo)
            }

            override fun onAdImpression() {

            }

            override fun onAdClicked() {

            }

            override fun onAdClosed() {
                AdLogger.interstitialClosed(label(mode))
            }
        }
        adView.loadAd(bannerRequest(mode == BannerMode.COLLAPSIBLE))
    }

    private fun closeDisplayed() {
        val record = displayed ?: return
        if (record.showStartMs > 0L) {

        }
        destroyAdView(record.adView)
        displayed = null
    }

    private fun destroyRecord(record: Record?) {
        record ?: return
        destroyAdView(record.adView)
    }

    private fun destroyAdView(adView: AdView?) {
        adView ?: return
        val parent = adView.parent as? ViewGroup
        parent?.removeView(adView)
        if (parent?.childCount == 0) {
            parent.visibility = View.GONE
        }
        adView.destroy()
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val ERROR_NO_UNITS = "Banner ad unit missing"
        private const val ERROR_ALL_FAILED = "Failed to load all banner ad units"

        private fun currentBannerSize(context: Context): AdSize {
            val metrics = context.resources.displayMetrics
            val widthDp = (metrics.widthPixels / metrics.density).toInt().coerceAtLeast(320)
            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
        }

        private fun bannerRequest(collapsible: Boolean): AdRequest {
            if (!collapsible) return AdRequest.Builder().build()
            val extras = Bundle().apply { putString("collapsible", "bottom") }
            return AdRequest.Builder()
                .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
                .build()
        }
    }
}
