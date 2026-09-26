package com.anony.bro.wser.data.track

/**
 * 埋点平台枚举。每个平台拥有独立的缓存队列、独立的初始化状态与独立的补发流程。
 */
enum class TrackPlatform(val id: String) {
    /** BI 平台，对应 com.flux.tracksdk.core.TrackSDK */
    BI("bi"),

    /** Firebase 平台，对应 com.google.firebase.analytics.FirebaseAnalytics */
    FIREBASE("firebase");

    companion object {
        fun fromId(id: String?): TrackPlatform? = entries.firstOrNull { it.id == id }
    }
}
