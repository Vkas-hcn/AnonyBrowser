package com.anony.bro.wser.data.track

import android.content.Context
import android.os.Bundle
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * 两个平台的真实上报器实现，以及 SDK 初始化状态探测工具。
 * 上报动作抛出异常即视为失败（返回 false），由 [TrackEventCacheManager] 负责重试/继续缓存。
 */
object TrackSdkUploaders {

    /** BI 平台（TrackSDK）上报器。 */
    fun bi(): TrackEventUploader = TrackEventUploader { event ->
        val props = if (event.properties.isEmpty()) null else HashMap<String, Any>(event.properties)
        TrackSDK.track(event.event, props, TrackPolicy.IMMEDIATE)
        true
    }

    /** Firebase 平台上报器。 */
    fun firebase(context: Context): TrackEventUploader {
        val appContext = context.applicationContext
        return TrackEventUploader { event ->
            val bundle = event.properties
                .takeIf { it.isNotEmpty() }
                ?.let { map -> Bundle().apply { map.forEach { (k, v) -> putString(k, v) } } }
            FirebaseAnalytics.getInstance(appContext).logEvent(event.event, bundle)
            true
        }
    }

    /** BI SDK 是否已初始化：TrackSDK.init 后其上下文非空。 */
    fun isBiInitialized(): Boolean = runCatching { TrackSDK.getContext() != null }.getOrDefault(false)

    /** Firebase 是否已初始化（FirebaseApp 已就绪）。 */
    fun isFirebaseInitialized(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context.applicationContext).isNotEmpty() }.getOrDefault(false)
}
