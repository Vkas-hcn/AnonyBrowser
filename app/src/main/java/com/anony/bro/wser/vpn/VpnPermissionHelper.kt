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

    fun requestNotificationPermission(activity: Activity) {
        if (!needsNotificationPermission(activity)) return
        markNotificationPermissionRequested(activity)
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE,
        )
    }

    fun requestNotificationPermissionWhenResumed(activity: Activity) {
        if (!needsNotificationPermission(activity)) return
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

    private const val PREFS_NOTIFICATION_PERMISSION = "notification_permission"
    private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
}
