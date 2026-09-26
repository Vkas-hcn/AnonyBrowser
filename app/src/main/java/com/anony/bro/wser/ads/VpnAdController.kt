package com.anony.bro.wser.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import com.anony.bro.wser.ads.AdLoadListener
import com.anony.bro.wser.ads.AdShowListener
import com.anony.bro.wser.data.vpn.VpnServerCatalogRepository

/**
 * VPN 广告协调器：
 * - 统一广告流程编排（超时、最短等待、回调收敛）
 * - 广告缓存不再基于 IP 切换
 */
object VpnAdController {
    private const val VIP_EXTRA_UNLOCK_SECONDS = 10 * 60L
    const val LOCAL_IP_KEY = "LOCAL"
    private const val INTERSTITIAL_SHARED_KEY = "INTERSTITIAL_SHARED"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var proUnlocked: Boolean = false
    @Volatile
    private var proUnlockedUntilElapsed: Long = 0L
    private val vipUnlockStateListeners = CopyOnWriteArraySet<() -> Unit>()
    private var proRelockRunnable: Runnable? = null
    
    // 记录已解锁的单个 VIP 服务器 key
    /**
     * @param minDelayMs 展示广告前的最小等待时间（用于断开/切换场景播放动画）。
     *                   即使广告已缓存，也会等到此时间后再展示。默认 0 表示无等待。
     * @param skipPostShowPreload 若为 true，广告关闭后不会自动预加载下一条。
     */
    fun showInterstitialThen(
        activity: Activity,
        minDelayMs: Long = 0L,
        skipPostShowPreload: Boolean = false,
        onAdPresented: (() -> Unit)? = null,
        onAdClosed: (() -> Unit)? = null,
        onDone: () -> Unit
    ): () -> Unit {
        val adKey = INTERSTITIAL_SHARED_KEY
        AdLogger.interstitialBegin(adKey, minDelayMs, skipPostShowPreload)

        val context = activity.applicationContext
        var completed = false
        var adShowing = false
        val startTime = SystemClock.elapsedRealtime()
        val loadTimeoutMs = AdMobManager.getIntersConnectLoadTimeoutMs()

        val timeoutRunnable = Runnable {
            if (completed || adShowing) return@Runnable
            completed = true
            AdLogger.interstitialTimeout(adKey, loadTimeoutMs)
            onDone()
        }
        mainHandler.postDelayed(timeoutRunnable, loadTimeoutMs)

        val cancel: () -> Unit = {
            if (!completed) {
                completed = true
                mainHandler.removeCallbacks(timeoutRunnable)
            }
        }

        val showListener = object : AdShowListener {
            override fun onAdShowed() {
                adShowing = true
                mainHandler.removeCallbacks(timeoutRunnable)
                AdLogger.interstitialPresented(adKey)
                onAdPresented?.invoke()
            }
            override fun onAdShowFailed(error: String) {
                if (completed) return
                completed = true
                mainHandler.removeCallbacks(timeoutRunnable)
                AdLogger.interstitialShowFailed(error)
                onDone()
            }
            override fun onAdClosed() {
                if (completed) return
                completed = true
                mainHandler.removeCallbacks(timeoutRunnable)
                AdLogger.interstitialClosed(adKey)
                (onAdClosed ?: onDone).invoke()
            }
            override fun onAdClicked() {}
        }

        fun doShowAd() {
            if (completed || adShowing) return
            val shown = AdMobManager.showInterstitialAd(activity, adKey, showListener, skipPostShowPreload)
            if (!shown) {
                AdMobManager.loadInterstitialAd(context, adKey, object : AdLoadListener {
                    override fun onAdLoaded() {
                        if (completed || adShowing) return
                        val shown2 = AdMobManager.showInterstitialAd(activity, adKey, showListener, skipPostShowPreload)
                        if (!shown2 && !completed) {
                            completed = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            onDone()
                        }
                    }
                    override fun onAdFailedToLoad(error: String) {
                        if (completed) return
                        completed = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        onDone()
                    }
                })
            }
        }

        fun showAfterMinDelay() {
            if (completed || adShowing) return
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val remaining = minDelayMs - elapsed
            if (remaining > 0) {
                AdLogger.interstitialWaitMinDelay(remaining, adKey)
                mainHandler.postDelayed({ doShowAd() }, remaining)
            } else {
                doShowAd()
            }
        }

        fun onLoadFailed(error: String) {
            if (completed) return
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val remaining = minDelayMs - elapsed
            if (remaining > 0) {
                mainHandler.postDelayed({
                    if (completed) return@postDelayed
                    completed = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    onDone()
                }, remaining)
            } else {
                completed = true
                mainHandler.removeCallbacks(timeoutRunnable)
                onDone()
            }
        }

        if (AdMobManager.isInterstitialAdLoadedForIp(adKey)) {
            AdLogger.interstitialCacheHit(adKey)
            showAfterMinDelay()
            return cancel
        }

        fun waitForLoadingAd() {
            mainHandler.postDelayed({
                if (completed || adShowing) return@postDelayed
                if (AdMobManager.isInterstitialAdLoadedForIp(adKey)) {
                    AdLogger.interstitialCacheHit(adKey)
                    showAfterMinDelay()
                } else {
                    waitForLoadingAd()
                }
            }, 100L)
        }

        AdLogger.interstitialCacheMiss(adKey)
        AdMobManager.loadInterstitialAd(context, adKey, object : AdLoadListener {
            override fun onAdLoaded() {
                showAfterMinDelay()
            }
            override fun onAdFailedToLoad(error: String) {
                onLoadFailed(error)
            }
        })
        waitForLoadingAd()
        return cancel
    }

    fun showOpenAdThen(activity: Activity, onDone: () -> Unit) {
        if (!AdMobManager.isOpenAdLoaded()) {
            onDone()
            return
        }
        val shown = AdMobManager.showOpenAd(
            activity,
            object : AdShowListener {
                override fun onAdShowed() = Unit

                override fun onAdShowFailed(error: String) {
                    onDone()
                }

                override fun onAdClosed() {
                    onDone()
                }

                override fun onAdClicked() = Unit
            }
        )
        if (!shown) {
            onDone()
        }
    }

    fun clearAllInterstitialCache() {
        AdMobManager.clearInterstitialCache()
    }

    fun isProUnlocked(): Boolean = isProUnlocked(SystemClock.elapsedRealtime())
    
    fun isVipServerUnlocked(serverKey: String? = null): Boolean = isProUnlocked(SystemClock.elapsedRealtime())

    fun addVipUnlockStateListener(listener: () -> Unit) {
        vipUnlockStateListeners.add(listener)
    }

    fun removeVipUnlockStateListener(listener: () -> Unit) {
        vipUnlockStateListeners.remove(listener)
    }

    private fun unlockProInSession(serverKey: String?) {
        proUnlocked = true
        proUnlockedUntilElapsed = vipUnlockDeadlineElapsed()
        scheduleProRelock(proUnlockedUntilElapsed)
        notifyVipUnlockStateChanged()
    }
    
    fun extendVipServerUnlock(serverKey: String? = null, extraSeconds: Long = VIP_EXTRA_UNLOCK_SECONDS) {
        val now = SystemClock.elapsedRealtime()
        val baseDeadline = proUnlockedUntilElapsed.takeIf { proUnlocked && it > now } ?: now
        proUnlocked = true
        proUnlockedUntilElapsed = baseDeadline + extraSeconds.coerceAtLeast(1L) * 1000L
        scheduleProRelock(proUnlockedUntilElapsed)
        notifyVipUnlockStateChanged()
    }

    private fun isProUnlocked(now: Long): Boolean {
        if (!proUnlocked) return false
        if (proUnlockedUntilElapsed > now) return true
        relockPro(notify = true)
        return false
    }

    private fun scheduleProRelock(deadlineElapsed: Long) {
        proRelockRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val runnable = Runnable {
            if (proUnlockedUntilElapsed <= SystemClock.elapsedRealtime()) {
                relockPro(notify = true)
            } else {
                scheduleProRelock(proUnlockedUntilElapsed)
            }
        }
        proRelockRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun relockPro(notify: Boolean) {
        proUnlocked = false
        proUnlockedUntilElapsed = 0L
        proRelockRunnable?.let { mainHandler.removeCallbacks(it) }
        proRelockRunnable = null
        if (notify) {
            notifyVipUnlockStateChanged()
        }
    }

    private fun vipUnlockDeadlineElapsed(): Long {
        val durationSeconds = getVipUnlockDurationSeconds().coerceAtLeast(1L)
        return SystemClock.elapsedRealtime() + durationSeconds * 1000L
    }

    private fun getVipUnlockDurationSeconds(): Long {
        val raw = VpnServerCatalogRepository.cachedOrEmpty().cacheLifetimeSec
        val parts = raw.orEmpty().split(",").map { it.trim() }
        val normal = parts.getOrNull(0)?.toLongOrNull() ?: 300L
        val vip = parts.getOrNull(1)?.toLongOrNull() ?: 600L
        return if (parts.size < 2 || normal <= 0L || vip <= 0L) 600L else vip
    }

    private fun notifyVipUnlockStateChanged() {
        vipUnlockStateListeners.forEach { listener ->
            runCatching { listener.invoke() }
        }
    }

}
