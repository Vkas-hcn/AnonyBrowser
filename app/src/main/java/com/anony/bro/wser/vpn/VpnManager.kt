package com.anony.bro.wser.vpn

import android.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.anony.bro.wser.R as AppR
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.vpn.DEFAULT_VPN_CONNECTION_DURATION_SECONDS
import java.lang.ref.WeakReference
import java.text.DecimalFormat

object VpnManager {

    private const val TAG = "VpnManager"
    private const val PREFS_NAME = "vpn_tracking_prefs"
    private const val KEY_VPN_PERMISSION_TRACKED = "vpn_permission_tracked"
    const val VPN_REQUEST_CODE = 10086

    var notificationTitle = "sing-box VPN"
    var notificationIcon = R.drawable.ic_dialog_info
    var notificationChannelId = "vpn_service_channel"
    var notificationChannelName = "VPN Service"
    var notificationActivityClass: Class<out Activity>? = null
    var autoRequestNotificationPermission = true

    private var appContext: Context? = null
    private var configJson: String = ""
    private val callbacks = mutableListOf<VpnCallback>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val df = DecimalFormat("0.00")

    private var pendingPermissionActivity: WeakReference<Activity>? = null
    private var pendingConnectReadyAction: (() -> Unit)? = null
    private var perAppProxyMode: PerAppProxyMode = PerAppProxyMode.DISABLED
    private val perAppProxyPackages = mutableSetOf<String>()
    private var connectionDurationSeconds = DEFAULT_VPN_CONNECTION_DURATION_SECONDS
    private var connectionDeadlineElapsedRealtime = 0L
    private var connectionTimeoutRunnable: Runnable? = null

    @Volatile
    var state: VpnState = VpnState.DISCONNECTED
        private set

    @Volatile
    private var runRequested = false

    @Volatile
    private var connectCompleteTrackedForRun = false

    @Volatile
    private var notificationsEnabledForSession = true

    @Volatile var uploadSpeed: Long = 0L; private set
    @Volatile var downloadSpeed: Long = 0L; private set
    @Volatile var totalUpload: Long = 0L; private set
    @Volatile var totalDownload: Long = 0L; private set

    internal var activeService: AnonyBrowserVpnService? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setConfig(
        json: String,
        connectionDurationSeconds: Long = DEFAULT_VPN_CONNECTION_DURATION_SECONDS,
    ) {
        configJson = json
        this.connectionDurationSeconds =
            connectionDurationSeconds.takeIf { it > 0 } ?: DEFAULT_VPN_CONNECTION_DURATION_SECONDS
    }

    fun getConfig(): String = configJson

    fun addCallback(callback: VpnCallback) {
        synchronized(callbacks) { callbacks.add(callback) }
    }

    fun removeCallback(callback: VpnCallback) {
        synchronized(callbacks) { callbacks.remove(callback) }
    }

    fun connect(activity: Activity) {
        prepareConnect(activity) { startVpn() }
    }

    fun prepareConnect(activity: Activity, onReady: () -> Unit): Boolean {
        if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) {
            Log.w(TAG, "VPN is already connected or connecting")
            return false
        }
        if (configJson.isBlank()) {
            notifyError(appString(AppR.string.vpn_set_config_before_connect))
            return false
        }

        return requestConnectionPermissionsInternal(activity, onReady)
    }

    fun requestConnectionPermissions(activity: Activity, onReady: () -> Unit): Boolean {
        if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) {
            Log.w(TAG, "VPN is already connected or connecting")
            return false
        }

        return requestConnectionPermissionsInternal(activity, onReady)
    }

    private fun requestConnectionPermissionsInternal(
        activity: Activity,
        onReady: () -> Unit,
    ): Boolean {
        pendingPermissionActivity = WeakReference(activity)
        pendingConnectReadyAction = onReady
        val vpnPermissionResult = runCatching { VpnService.prepare(activity) }
        if (vpnPermissionResult.isFailure) {
            val error = vpnPermissionResult.exceptionOrNull()
            clearPendingPermissionFlow()
            updateState(VpnState.DISCONNECTED)
            notifyError(appString(AppR.string.vpn_permission_check_failed_format, error?.message.orEmpty()))
            return false
        }
        val vpnPermissionIntent = vpnPermissionResult.getOrNull()

        if (vpnPermissionIntent != null) {
            return runCatching {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(vpnPermissionIntent, VPN_REQUEST_CODE)
            }.onFailure {
                clearPendingPermissionFlow()
                updateState(VpnState.DISCONNECTED)
                notifyError(appString(AppR.string.vpn_permission_request_failed_format, it.message.orEmpty()))
            }.isSuccess
        }

        requestNotificationPermissionThenReady(activity)
        return true
    }

    fun prepareVpn(context: Context): Intent? = VpnService.prepare(context)

    fun startVpn() {
        if (configJson.isBlank()) {
            notifyError(appString(AppR.string.vpn_set_config_before_connect))
            return
        }
        startService()
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != VPN_REQUEST_CODE) return false

        runCatching {
            if (resultCode == Activity.RESULT_OK) {
                trackVpnPermissionGrantedOnce()
                val activity = pendingPermissionActivity?.get()
                if (activity != null) {
                    requestNotificationPermissionThenReady(activity)
                } else {
                    notificationsEnabledForSession = false
                    completeConnectionPermissionFlow()
                }
            } else {
                UpDataTool.trackEvent("vpn_per_reject")
                clearPendingPermissionFlow()
                runRequested = false
                updateState(VpnState.DISCONNECTED)
                notifyError(appString(AppR.string.vpn_permission_denied))
            }
        }.onFailure {
            clearPendingPermissionFlow()
            notificationsEnabledForSession = false
            updateState(VpnState.DISCONNECTED)
            notifyError(appString(AppR.string.vpn_permission_result_failed_format, it.message.orEmpty()))
        }
        return true
    }

    private fun trackVpnPermissionGrantedOnce() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_VPN_PERMISSION_TRACKED, false)) return
        prefs.edit()
            .putBoolean(KEY_VPN_PERMISSION_TRACKED, true)
            .apply()
        UpDataTool.trackEvent("vpn_per")
    }

    fun handlePermissionsResult(
        activity: Activity,
        requestCode: Int,
        grantResults: IntArray,
    ): Boolean {
        return VpnPermissionHelper.handlePermissionResult(
            requestCode = requestCode,
            grantResults = grantResults,
            onCompleted = { granted ->
                notificationsEnabledForSession = granted
                completeConnectionPermissionFlow()
            },
            onError = {
                notificationsEnabledForSession = false
                notifyError(appString(AppR.string.vpn_notification_permission_result_failed_format, it.message.orEmpty()))
            },
        )
    }

    fun disconnect() {
        runRequested = false
        connectCompleteTrackedForRun = false
        stopConnectionTimer()
        activeService?.disconnect() ?: run {
            val ctx = appContext ?: return
            ctx.stopService(Intent(ctx, AnonyBrowserVpnService::class.java))
            updateState(VpnState.DISCONNECTED)
        }
    }

    fun isConnected(): Boolean = state == VpnState.CONNECTED

    fun getConnectionRemainingSeconds(): Int {
        if (state != VpnState.CONNECTED || connectionDeadlineElapsedRealtime <= 0L) return 0
        val remainingMs = (connectionDeadlineElapsedRealtime - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        return ((remainingMs + 999) / 1_000).toInt()
    }

    fun setPerAppProxy(mode: PerAppProxyMode, packages: Set<String>) {
        perAppProxyMode = mode
        perAppProxyPackages.clear()
        perAppProxyPackages.addAll(packages)
    }

    fun addProxyPackage(packageName: String) {
        perAppProxyPackages.add(packageName)
    }

    fun removeProxyPackage(packageName: String) {
        perAppProxyPackages.remove(packageName)
    }

    fun clearProxyPackages() {
        perAppProxyPackages.clear()
    }

    fun getPerAppProxyMode(): PerAppProxyMode = perAppProxyMode

    fun getProxyPackages(): Set<String> = perAppProxyPackages.toSet()

    internal fun getEffectiveProxyConfig(): Pair<PerAppProxyMode, Set<String>> =
        Pair(perAppProxyMode, perAppProxyPackages.toSet())

    fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> "${df.format(bytesPerSecond / 1024.0)} KB/s"
        else -> "${df.format(bytesPerSecond / (1024.0 * 1024.0))} MB/s"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${df.format(bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${df.format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }

    private fun requestNotificationPermissionThenReady(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationsEnabledForSession = true
            completeConnectionPermissionFlow()
            return
        }

        if (!autoRequestNotificationPermission) {
            notificationsEnabledForSession = !VpnPermissionHelper.needsNotificationPermission(activity)
            completeConnectionPermissionFlow()
            return
        }

        if (!VpnPermissionHelper.needsNotificationPermission(activity)) {
            notificationsEnabledForSession = true
            completeConnectionPermissionFlow()
            return
        }

        runCatching {
            VpnPermissionHelper.requestNotificationPermissionWhenResumed(activity)
        }.onFailure {
            notificationsEnabledForSession = false
            notifyError(appString(AppR.string.vpn_notification_permission_request_failed_format, it.message.orEmpty()))
            completeConnectionPermissionFlow()
        }
    }

    private fun completeConnectionPermissionFlow() {
        val action = pendingConnectReadyAction
        clearPendingPermissionFlow()
        action?.invoke() ?: startVpn()
    }

    private fun clearPendingPermissionFlow() {
        pendingPermissionActivity = null
        pendingConnectReadyAction = null
    }

    private fun startService() {
        val ctx = appContext ?: run {
            notifyError(appString(AppR.string.vpn_not_initialized))
            return
        }
        clearPendingPermissionFlow()
        runRequested = true
        connectCompleteTrackedForRun = false
        resetTraffic()
        updateState(VpnState.CONNECTING)

        val serviceIntent = Intent(ctx, AnonyBrowserVpnService::class.java)
        if (notificationsEnabledForSession) {
            ContextCompat.startForegroundService(ctx, serviceIntent)
        } else {
            ctx.startService(serviceIntent)
        }
    }

    private fun resetTraffic() {
        uploadSpeed = 0
        downloadSpeed = 0
        totalUpload = 0
        totalDownload = 0
    }

    internal fun onServiceCreated(service: AnonyBrowserVpnService) {
        activeService = service
    }

    internal fun isRunRequested(): Boolean = runRequested

    internal fun shouldShowNotifications(): Boolean = notificationsEnabledForSession

    internal fun disableNotificationsForSession() {
        notificationsEnabledForSession = false
    }

    internal fun onServiceDestroyed() {
        activeService = null
        runRequested = false
        stopConnectionTimer()
        if (state != VpnState.DISCONNECTED) {
            updateState(VpnState.DISCONNECTED)
        }
    }

    internal fun updateState(newState: VpnState) {
        if (state == newState) return
        state = newState
        when (newState) {
            VpnState.CONNECTED -> startConnectionTimer()
            VpnState.DISCONNECTED,
            VpnState.CONNECTING,
            VpnState.DISCONNECTING -> stopConnectionTimer()
        }
        if (newState == VpnState.CONNECTED && runRequested && !connectCompleteTrackedForRun) {
            connectCompleteTrackedForRun = true
            UpDataTool.trackEvent("vpn_complete_connect")
        } else if (newState == VpnState.DISCONNECTED) {
            connectCompleteTrackedForRun = false
        }
        mainHandler.post {
            synchronized(callbacks) {
                callbacks.forEach { it.onStateChanged(newState) }
            }
        }
    }

    internal fun updateTraffic(upSpeed: Long, downSpeed: Long, totalUp: Long, totalDown: Long) {
        uploadSpeed = upSpeed
        downloadSpeed = downSpeed
        totalUpload = totalUp
        totalDownload = totalDown
        mainHandler.post {
            synchronized(callbacks) {
                callbacks.forEach { it.onTrafficUpdate(upSpeed, downSpeed, totalUp, totalDown) }
            }
        }
    }

    internal fun notifyError(message: String) {
        Log.e(TAG, message)
        mainHandler.post {
            synchronized(callbacks) {
                callbacks.forEach { it.onError(message) }
            }
        }
    }

    private fun startConnectionTimer() {
        stopConnectionTimer()
        val durationMs = connectionDurationSeconds
            .coerceAtMost(Long.MAX_VALUE / 1_000L)
            .times(1_000L)
        connectionDeadlineElapsedRealtime = SystemClock.elapsedRealtime() + durationMs
        scheduleConnectionTimeout()
    }

    private fun scheduleConnectionTimeout() {
        val remainingMs = (connectionDeadlineElapsedRealtime - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        connectionTimeoutRunnable = Runnable {
            connectionTimeoutRunnable = null
            if (state == VpnState.CONNECTED && runRequested) {
                Log.i(TAG, "VPN connection duration expired, disconnecting")
                disconnect()
            }
        }.also { mainHandler.postDelayed(it, remainingMs) }
    }

    private fun stopConnectionTimer() {
        connectionTimeoutRunnable?.let(mainHandler::removeCallbacks)
        connectionTimeoutRunnable = null
        connectionDeadlineElapsedRealtime = 0L
    }

    private fun appString(resId: Int, vararg args: Any?): String =
        appContext?.getString(resId, *args)
            ?: run {
                val arg = args.firstOrNull()?.toString().orEmpty()
                when (resId) {
                    AppR.string.vpn_set_config_before_connect -> "Please call setConfig() before connecting VPN"
                    AppR.string.vpn_permission_check_failed_format -> "VPN permission check failed: $arg"
                    AppR.string.vpn_permission_request_failed_format -> "VPN permission request failed: $arg"
                    AppR.string.vpn_permission_denied -> "VPN permission denied"
                    AppR.string.vpn_permission_result_failed_format -> "VPN permission result failed: $arg"
                    AppR.string.vpn_notification_permission_result_failed_format -> "Notification permission result failed: $arg"
                    AppR.string.vpn_notification_permission_request_failed_format -> "Notification permission request failed: $arg"
                    AppR.string.vpn_not_initialized -> "VpnManager is not initialized"
                    else -> ""
                }
            }
}
