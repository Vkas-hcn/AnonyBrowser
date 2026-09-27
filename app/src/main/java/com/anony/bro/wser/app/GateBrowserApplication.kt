package com.anony.bro.wser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.edit
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.AdjustSessionFailure
import com.adjust.sdk.AdjustSessionSuccess
import com.adjust.sdk.LogLevel
import com.adjust.sdk.OnSessionTrackingFailedListener
import com.adjust.sdk.OnSessionTrackingSucceededListener
import com.flux.tracksdk.core.TrackConfig
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.google.android.gms.ads.AdActivity
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.base.BrowserTabViewModelStore
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.data.DataHubTool
import com.anony.bro.wser.data.RetentionTracking
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.settings.AppLanguageStore
import com.anony.bro.wser.guide.BrowserGuideControl
import com.anony.bro.wser.hellohello.HintUtil
import com.anony.bro.wser.hellohello.NoticeConfigStore
import com.anony.bro.wser.hellohello.VpnReminderNotifier
import com.anony.bro.wser.hellohello.fcm.FcmNotificationDecision
import com.anony.bro.wser.view.guide.GuideActivity
import com.anony.bro.wser.vpn.VpnBarLauncher
import com.anony.bro.wser.vpn.VpnManager
import com.anony.bro.wser.vpn.VpnState
import com.anony.bro.wser.vpn.VpnStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class GateBrowserApplication : Application(), DefaultLifecycleObserver {

    private var hotStartReady = false
    private var skipNextHotStart = false
    private var topActivity: WeakReference<Activity>? = null
    private var adActivity: WeakReference<Activity>? = null

    @Volatile
    private var processInForeground = true

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageStore.wrap(base))
    }

    override fun onCreate() {
        super<Application>.onCreate()
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(AppLanguageStore.selectedTag(this)),
        )
        instance = this
        initTrackSdk()
        // BI（TrackSDK）已同步初始化完成，触发 BI 平台缓存补发。
        UpDataTool.onBiInitialized()
        // Firebase 通过其 ContentProvider 在进程启动时自动初始化，此处标记并触发补发。
        UpDataTool.onFirebaseInitialized()
        registerActivityLifecycleCallbacks(HotStartActivityCallbacks())
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        VpnManager.init(applicationContext)
        VpnStatsStore.init()
        initAdJust(this)
        DataHubTool.addVpnDataListener(AdMobManager::refreshConfig)
        NoticeConfigStore.init(this)
        VpnReminderNotifier.init(this)
        DataHubTool.addVpnDataListener {
            NoticeConfigStore.cacheNotice(DataHubTool.vpnData)
            VpnReminderNotifier.onConfigChanged()
            VpnBarLauncher.refreshNewsConfig(this)
        }
        BrowserGuideControl.init()
        DataHubTool.init(this)
        HintUtil.init(this)
        HintUtil.startJob(this)
        FcmNotificationDecision.onRuntimeReady()
        AdMobManager.initialize(this) { }
    }

    override fun onStart(owner: LifecycleOwner) {
        processInForeground = true
        AdMobManager.updateAppForegroundState(true)
        // 应用回到前台时确保 VPN 常驻栏存活（前台启动，规避后台 FGS 启动限制）。
        VpnBarLauncher.ensureRunning(this)
        flushTrackEvents()
        UpDataTool.purgeExpiredCache()
        // 回访（含冷启动）时检查留存里程碑，命中则上报；内部在 IO 线程执行，不阻塞主线程。
        RetentionTracking.track(this)
        if (skipNextHotStart) {
            skipNextHotStart = false
            hotStartReady = false
            Log.d(TAG, "Hot start skipped after opening notification settings")
            return
        }
        if (!hotStartReady) return

        hotStartReady = false
        if (topActivity?.get() is GuideActivity) {
            Log.d(TAG, "Hot start skipped: GuideActivity already in foreground")
            return
        }
        if (VpnManager.state == VpnState.CONNECTING || VpnManager.state == VpnState.DISCONNECTING) {
            Log.d(TAG, "Hot start skipped while VPN is ${VpnManager.state}")
            return
        }
        launchGuideForHotStart()
    }

    override fun onStop(owner: LifecycleOwner) {
        processInForeground = false
        AdMobManager.updateAppForegroundState(false)
        flushTrackEvents()
        hotStartReady = true
        adActivity?.get()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?.finish()
        Log.d(TAG, "Hot start is ready")
    }

    private fun launchGuideForHotStart() {
        val currentActivity = topActivity?.get()
        val intent = GuideActivity.createHotStartIntent(this)
        if (currentActivity != null && !currentActivity.isFinishing && !currentActivity.isDestroyed) {
            currentActivity.startActivity(intent)
            Log.d(
                TAG,
                "Hot start launched GuideActivity from ${currentActivity.javaClass.simpleName}"
            )
        } else {
            startActivity(intent)
            Log.d(TAG, "Hot start launched GuideActivity from application context")
        }
        UpDataTool.trackEvent("hot_restart")
    }

    /** Prevents the return from an external settings page from triggering the hot-start guide. */
    fun skipNextHotStart() {
        skipNextHotStart = true
        hotStartReady = false
    }

    fun isAppForeground(): Boolean = processInForeground

    private inner class HotStartActivityCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            updateActivityReference(activity)

        override fun onActivityStarted(activity: Activity) = updateActivityReference(activity)

        override fun onActivityResumed(activity: Activity) {
            updateActivityReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (topActivity?.get() === activity) topActivity = null
            if (adActivity?.get() === activity) adActivity = null
        }

        private fun updateActivityReference(activity: Activity) {
            if (activity is AdActivity) {
                adActivity = WeakReference(activity)
            } else {
                topActivity = WeakReference(activity)
            }
        }
    }

    fun initTrackSdk() {
        val trackConfig = TrackConfig.Builder(this)
            .baseUrl(if (BuildConfig.DEBUG) "https://testbi.googletogoogle.com" else "https://bi.googletogoogle.com")
            .debug(BuildConfig.DEBUG)
            .appId(packageName)
            .channel("google_play")
            .batchSize(20)
            .defaultUploadPolicy(TrackPolicy.BATCH)
            .uploadThreadCount(2)
            .maxRetryCount(5)
            .connectTimeout(30_000)
            .readTimeout(30_000)
            .enablePiggyback(true)
            .enableAppList(false)
            .build()
        TrackSDK.init(trackConfig)
    }

    fun flushTrackEvents() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        val capabilities = connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities) ?: return
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            TrackSDK.flush()
        }
    }

    @SuppressLint("HardwareIds")
    private fun initAdJust(application: Application) {
        val deviceId = TrackSDK.getInstance().effectiveDistinctId
        val appToken = "t5qigkbcfy0w"
        val environment: String = if (BuildConfig.DEBUG) {
            AdjustConfig.ENVIRONMENT_SANDBOX
        } else {
            AdjustConfig.ENVIRONMENT_PRODUCTION
        }
        val config = AdjustConfig(application, appToken, environment)
        config.setLogLevel(LogLevel.WARN)
        config.setExternalDeviceId(deviceId)
        config.enableFirstSessionDelay()
        config.enableSendingInBackground()
        Adjust.initSdk(config)
        Adjust.endFirstSessionDelay()
    }

    companion object {
        private const val TAG = "GateBrowserApp"

        @Volatile
        private var instance: GateBrowserApplication? = null
        fun get(): GateBrowserApplication =
            instance ?: error("Application not initialized")
    }
}
