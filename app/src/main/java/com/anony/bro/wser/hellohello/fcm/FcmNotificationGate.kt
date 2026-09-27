package com.anony.bro.wser.hellohello.fcm

/**
 * FCM 展示门禁（无 Android 依赖），与运营 Config / 频控 / 时段无关。
 */
internal object FcmNotificationGate {
    fun blockReason(
        runtimeInitialized: Boolean,
        isForeground: Boolean,
        isKrSamsung: Boolean,
        hasNotificationPermission: Boolean,
    ): String? {
        if (!runtimeInitialized) return "runtime_not_initialized"
        if (isKrSamsung) return "kr_samsung"
        if (isForeground) return "app_foreground"
        if (!hasNotificationPermission) return "permission_denied"
        return null
    }
}
