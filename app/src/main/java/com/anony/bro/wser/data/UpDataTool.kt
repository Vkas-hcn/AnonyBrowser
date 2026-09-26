package com.anony.bro.wser.data


import android.util.Log
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustEvent
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.track.PendingTrackEvent
import com.anony.bro.wser.data.track.SharedPrefsTrackEventPersistence
import com.anony.bro.wser.data.track.TrackEventCacheManager
import com.anony.bro.wser.data.track.TrackEventStore
import com.anony.bro.wser.data.track.TrackLogger
import com.anony.bro.wser.data.track.TrackPlatform
import com.anony.bro.wser.data.track.TrackSdkUploaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object UpDataTool {

    private const val TAG = "trackEvent"

    private val trackingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val androidLogger = TrackLogger { level, message, error ->
        when (level) {
            TrackLogger.Level.DEBUG -> Log.d(TAG, message)
            TrackLogger.Level.WARN -> Log.w(TAG, message, error)
            TrackLogger.Level.ERROR -> Log.e(TAG, message, error)
        }
    }

    /**
     * 埋点缓存补发系统入口。首次访问时基于 SharedPreferences 构建持久化缓存，
     * 并根据当前 SDK 状态恢复初始化标记（页面/进程重建场景下 SDK 可能已就绪）。
     */
    private val manager: TrackEventCacheManager by lazy {
        val context = GateBrowserApplication.get().applicationContext
        val store = TrackEventStore(
            persistence = SharedPrefsTrackEventPersistence(context),
            logger = androidLogger,
        )
        TrackEventCacheManager(
            store = store,
            uploaders = mapOf(
                TrackPlatform.BI to TrackSdkUploaders.bi(),
                TrackPlatform.FIREBASE to TrackSdkUploaders.firebase(context),
            ),
            backoff = { attempt -> runCatching { Thread.sleep(minOf(attempt * 500L, 2_000L)) } },
            logger = androidLogger,
        ).also { mgr ->
            val biReady = TrackSdkUploaders.isBiInitialized()
            val firebaseReady = TrackSdkUploaders.isFirebaseInitialized(context)
            Log.d(TAG, "SDK init status on build: BI initialized=$biReady, Firebase initialized=$firebaseReady")
            if (biReady) mgr.markInitialized(TrackPlatform.BI)
            if (firebaseReady) mgr.markInitialized(TrackPlatform.FIREBASE)
        }
    }

    fun trackEvent(
        event: String,
        properties: Map<String, String>? = null,
    ) {
        trackingScope.launch {
            manager.track(
                event = event,
                properties = properties.orEmpty(),
                eventType = PendingTrackEvent.TYPE_CUSTOM,
            )
        }
    }

    /** BI SDK（TrackSDK）初始化完成后调用，校验并打印初始化状态，成功则触发 BI 平台缓存补发。 */
    fun onBiInitialized() {
        trackingScope.launch {
            val ready = TrackSdkUploaders.isBiInitialized()
            Log.d(TAG, "SDK init status: BI initialized=$ready")
            if (ready) {
                val result = manager.markInitialized(TrackPlatform.BI)
                Log.d(TAG, "BI init success, replay result=$result")
            } else {
                Log.w(TAG, "BI init not ready, skip replay")
            }
        }
    }

    /** Firebase 初始化完成后调用，校验并打印初始化状态，成功则触发 Firebase 平台缓存补发。 */
    fun onFirebaseInitialized() {
        trackingScope.launch {
            val context = GateBrowserApplication.get().applicationContext
            val ready = TrackSdkUploaders.isFirebaseInitialized(context)
            Log.d(TAG, "SDK init status: Firebase initialized=$ready")
            if (ready) {
                val result = manager.markInitialized(TrackPlatform.FIREBASE)
                Log.d(TAG, "Firebase init success, replay result=$result")
            } else {
                Log.w(TAG, "Firebase init not ready, skip replay")
            }
        }
    }

    /** 主动清理过期缓存（>7 天），可在应用进入前台等时机调用。 */
    fun purgeExpiredCache() {
        trackingScope.launch { manager.purgeExpired() }
    }

    fun adjustPoint(
        key: String
    ) {
        val token = adjustTokenFor(key) ?: return
        runCatching {
            val adjustEvent = AdjustEvent(token)
            Adjust.trackEvent(adjustEvent)
        }.onFailure {
            Log.e(TAG, "adjustPoint failed: $key", it)
        }
    }

    private fun adjustTokenFor(key: String): String? {
        return ADJUST_TOKENS[key]
    }

    private val ADJUST_TOKENS = mapOf(
        "click_search" to "iqvh9l",
        "favorites_click_amz" to "9su157",
        "favorites_click_fb" to "4kwsyc",
        "favorites_click_tt" to "ekdklu",
        "favorites_click_wk" to "d8fjgl",
        "favorites_click_x" to "92zkh7",
        "favorites_click_yt" to "pd3e4e",
        "home_view" to "gvr882",
        "hot_restart" to "6euyf7",
        "loading" to "8ct54p",
        "news_notification_click" to "m73b9m",
        "news_notification_sent" to "fpmbpo",
        "notification_agree" to "3b2k7f",
        "notification_reject" to "sljime",
        "set_default_browser" to "e7h1ff",
        "vpn_complete_connect" to "8mgp42",
        "vpn_notification_click" to "yzbbxk",
        "vpn_notification_sent" to "iowxoi",
        "vpn_per" to "af1so2",
        "vpn_per_reject" to "d1wjcr",
        "vpn_start_connect" to "6d9out",
    )
}
