package com.anony.bro.wser.data


import android.util.Log
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustEvent
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.google.firebase.analytics.FirebaseAnalytics
import com.anony.bro.wser.app.GateBrowserApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object UpDataTool {

    private const val TAG = "trackEvent"

    private val trackingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun trackEvent(
        event: String,
    ) {
        trackingScope.launch {
            firebasePoint(event)
            TrackSDK.track(event, null, TrackPolicy.IMMEDIATE)
            Log.d(TAG, "trackEvent: $event")
        }
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

    private fun firebasePoint(event: String) {
        runCatching {
            val context = GateBrowserApplication.get()
            FirebaseAnalytics.getInstance(context).logEvent(event, null)
        }.onFailure {
            Log.e(TAG, "firebasePoint failed: $event", it)
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
