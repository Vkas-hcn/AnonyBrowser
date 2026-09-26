package com.anony.bro.wser.vpn

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.data.UpDataTool

/**
 * VPN 通知权限助手。
 *
 * Android 13 (API 33)+ 需要运行时申请 [Manifest.permission.POST_NOTIFICATIONS]，
 * 否则前台服务通知无法展示。
 */
object VpnPermissionHelper {

    const val NOTIFICATION_PERMISSION_REQUEST_CODE = 10087

    fun isNotificationPermissionRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun hasNotificationPermission(activity: Activity): Boolean {
        if (!isNotificationPermissionRequired()) return true
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * @return true 表示仍需申请运行时通知权限
     */
    fun needsNotificationPermission(activity: Activity): Boolean =
        !hasNotificationPermission(activity)

    fun markNotificationPermissionRequested(context: Context) {
        context.getSharedPreferences(PREFS_NOTIFICATION_PERMISSION, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            .apply()
    }

    fun hasRequestedNotificationPermission(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NOTIFICATION_PERMISSION, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

    /**
     * Android will not show the runtime permission dialog again once notification permission
     * has been permanently denied. The persisted request marker distinguishes that state from
     * the initial request, where the rationale API also returns false.
     */
    fun shouldShowNotificationSettingsGuide(activity: Activity): Boolean =
            isNotificationPermissionRequired() &&
            !hasNotificationPermission(activity) &&
            hasRequestedNotificationPermission(activity) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            )

    fun openNotificationSettings(activity: Activity): Boolean {
        val notificationSettingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        if (runCatching { activity.startActivity(notificationSettingsIntent) }.isSuccess) return true

        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        return runCatching { activity.startActivity(appDetailsIntent) }.isSuccess
    }

    fun canShowSystemPermissionDialog(activity: Activity): Boolean =
        NotificationPermissionTracking.shouldLaunchRuntimeDialog(
            sdkInt = Build.VERSION.SDK_INT,
            granted = hasNotificationPermission(activity),
            alreadyRequested = hasRequestedNotificationPermission(activity),
            shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )

    fun trackRuntimeResult(granted: Boolean, scene: String) {
        trackPermissionEvent(NotificationPermissionTracking.eventName(granted), scene)
    }

    fun trackSettingsGuideResult(granted: Boolean) {
        trackRuntimeResult(granted, NotificationPermissionTracking.SCENE_SETTINGS_GUIDE)
    }

    fun trackLegacyAutoAgreeIfNeeded(context: Context) {
        if (!NotificationPermissionTracking.shouldTrackLegacyAutoAgree(
                sdkInt = Build.VERSION.SDK_INT,
                alreadyTracked = hasTrackedLegacyAutoAgree(context),
            )
        ) return
        markLegacyAutoAgreeTracked(context)
        trackPermissionEvent(
            NotificationPermissionTracking.EVENT_AGREE,
            NotificationPermissionTracking.SCENE_LEGACY_AUTO,
        )
    }

    fun requestNotificationPermission(activity: Activity) {
        if (!canShowSystemPermissionDialog(activity)) return
        markNotificationPermissionRequested(activity)
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE,
        )
    }

    fun requestNotificationPermissionWhenResumed(activity: Activity) {
        if (!canShowSystemPermissionDialog(activity)) return
        val lifecycleOwner = activity as? LifecycleOwner ?: run {
            requestNotificationPermission(activity)
            return
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            requestNotificationPermission(activity)
            return
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                requestNotificationPermission(activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    /**
     * 处理权限申请结果。
     * @return true 表示已处理本请求
     */
    fun handlePermissionResult(
        requestCode: Int,
        grantResults: IntArray?,
        onCompleted: (Boolean) -> Unit,
        onError: (Throwable) -> Unit = {},
    ): Boolean {
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST_CODE) return false

        runCatching {
            grantResults?.firstOrNull() == PackageManager.PERMISSION_GRANTED
        }.onSuccess { granted ->
            onCompleted(granted)
        }.onFailure { error ->
            onError(error)
            onCompleted(false)
        }
        return true
    }

    private fun trackPermissionEvent(event: String, scene: String) {
        UpDataTool.trackEvent(event, NotificationPermissionTracking.contextParams(scene))
    }

    private fun hasTrackedLegacyAutoAgree(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NOTIFICATION_PERMISSION, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEGACY_AUTO_AGREE_TRACKED, false)

    private fun markLegacyAutoAgreeTracked(context: Context) {
        context.getSharedPreferences(PREFS_NOTIFICATION_PERMISSION, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LEGACY_AUTO_AGREE_TRACKED, true)
            .apply()
    }

    private const val PREFS_NOTIFICATION_PERMISSION = "notification_permission"
    private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    private const val KEY_LEGACY_AUTO_AGREE_TRACKED = "legacy_auto_agree_tracked"
}

object NotificationPermissionTracking {
    const val EVENT_AGREE = "notification_agree"
    const val EVENT_REJECT = "notification_reject"
    const val SCENE_STARTUP = "startup"
    const val SCENE_VPN = "vpn"
    const val SCENE_SETTINGS_GUIDE = "settings_guide"
    const val SCENE_LEGACY_AUTO = "legacy_auto"
    const val KEY_SCENE = "scene"
    const val KEY_OS_VERSION = "os_version"
    const val KEY_APP_VERSION = "app_version"

    fun eventName(granted: Boolean): String =
        if (granted) EVENT_AGREE else EVENT_REJECT

    fun contextParams(
        scene: String,
        osVersion: Int = Build.VERSION.SDK_INT,
        appVersion: String = BuildConfig.VERSION_NAME,
    ): Map<String, String> = mapOf(
        KEY_SCENE to scene,
        KEY_OS_VERSION to osVersion.toString(),
        KEY_APP_VERSION to appVersion,
    )

    fun shouldLaunchRuntimeDialog(
        sdkInt: Int,
        granted: Boolean,
        alreadyRequested: Boolean,
        shouldShowRationale: Boolean,
    ): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return false
        if (granted) return false
        return !alreadyRequested || shouldShowRationale
    }

    fun shouldTrackLegacyAutoAgree(sdkInt: Int, alreadyTracked: Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU && !alreadyTracked

    fun settingsGuideDismissAction(
        programmatic: Boolean,
        confirmOpenedSettings: Boolean,
    ): SettingsGuideDismissAction = when {
        programmatic -> SettingsGuideDismissAction.IGNORE
        confirmOpenedSettings -> SettingsGuideDismissAction.WAIT_SETTINGS
        else -> SettingsGuideDismissAction.TRACK_REJECT
    }
}

enum class SettingsGuideDismissAction { TRACK_REJECT, WAIT_SETTINGS, IGNORE }

class SettingsGuideSession {
    var awaitingSettingsResult = false
        private set
    private var reported = false

    fun consumeDismiss(
        programmatic: Boolean,
        confirmOpenedSettings: Boolean,
    ): Boolean? {
        if (reported) return null
        return when (
            NotificationPermissionTracking.settingsGuideDismissAction(
                programmatic,
                confirmOpenedSettings,
            )
        ) {
            SettingsGuideDismissAction.TRACK_REJECT -> {
                reported = true
                false
            }
            SettingsGuideDismissAction.WAIT_SETTINGS -> {
                awaitingSettingsResult = true
                null
            }
            SettingsGuideDismissAction.IGNORE -> null
        }
    }

    fun consumeSettingsReturn(granted: Boolean): Boolean? {
        if (!awaitingSettingsResult || reported) return null
        awaitingSettingsResult = false
        reported = true
        return granted
    }

    fun restoreAwaiting(awaiting: Boolean) {
        awaitingSettingsResult = awaiting
    }
}
