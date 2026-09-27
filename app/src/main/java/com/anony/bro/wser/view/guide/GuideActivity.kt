package com.anony.bro.wser.view.guide

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.anony.bro.wser.view.main.MainActivity
import com.anony.bro.wser.R
import com.anony.bro.wser.base.BaseActivity
import com.anony.bro.wser.ads.AdLoadListener
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.ads.AdShowListener
import com.anony.bro.wser.data.LoadingTracking
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.guide.LeadStore
import com.anony.bro.wser.databinding.ActivityGuideBinding
import com.anony.bro.wser.guide.BrowserGuideControl
import com.anony.bro.wser.hellohello.HintUtil
import com.anony.bro.wser.hellohello.VpnReminderNotifier
import com.anony.bro.wser.view.vpn.VpnActivity
import com.anony.bro.wser.vpn.NotificationPermissionTracking
import com.anony.bro.wser.vpn.VpnBarLauncher
import com.anony.bro.wser.vpn.VpnPermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 引导页：按待办列表展示通知权限 / 默认浏览器设置，全部满足后进入 [MainActivity]。
 *
 * - 渲染前过滤无需申请与已授权项
 * - 初始化时若已是默认浏览器且其余权限已满足，直接进首页
 * - 沉浸式状态栏：透明系统栏 + 图标颜色自适应 + 内容避让 insets
 */
class GuideActivity : BaseActivity<ActivityGuideBinding, GuideViewModel>() {

    override val viewModel: GuideViewModel by viewModels()

    /** 启用 edge-to-edge，由本页自定义 insets，避免根布局整体 padding 破坏全屏背景 */
    override val edgeToEdgeEnabled: Boolean = true
    override val applySystemBarPadding: Boolean = false

    private var hasNavigatedToMain = false
    private var splashSession = 0
    private var initialProgressCompleted = false
    private var notificationPermissionResolved = false
    private var notificationPermissionRequestInFlight = false
    private var notificationPermissionRequestPending = false
    private val startupAdHandler = Handler(Looper.getMainLooper())
    private var startupAdTimeout: Runnable? = null
    private var startupAdFlowStarted = false
    private var startupAdFlowCompleted = false
    private var startupAdShowing = false
    private var startupOpenAdState = StartupAdLoadState.NOT_STARTED
    private var startupGuide1AdState = StartupAdLoadState.NOT_STARTED
    private var startupGuide2AdState = StartupAdLoadState.NOT_STARTED
    private var homeNativePreloadScheduled = false
    private var browserGuideInFlight = false
    private var umpFlowStarted = false
    private var umpFlowCompleted = false
    private var openAfterGuideFromVpnReminder = false
    private var pendingOpenUri: Uri? = null
    private var pendingNewsShowType: Int? = null
    private var pendingNewsNotificationId: Int? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // 无论授权或拒绝，本步骤已展示过，前进到下一步，绝不回退
            VpnPermissionHelper.trackRuntimeResult(
                isGranted,
                NotificationPermissionTracking.SCENE_STARTUP,
            )
            if (isGranted) VpnBarLauncher.ensureRunning(this)
            notificationPermissionRequestInFlight = false
            completeNotificationPermissionStep()
        }

    private val defaultBrowserGuideLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 从系统页返回：若已设为默认浏览器则结束；否则留在浏览器步骤（不回到通知页）
            browserGuideInFlight = false
            viewModel.dismissStep(GuideStep.BROWSER)
            resolveGuideState(navigateIfComplete = true)
        }

    override fun inflateBinding(inflater: LayoutInflater): ActivityGuideBinding =
        ActivityGuideBinding.inflate(inflater)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        trackNewsNotificationClickIfNeeded(intent)
        trackLoadingShow(intent)
        rememberNotificationLaunch(this.intent)
        rememberNotificationLaunch(intent)
        if (openAfterGuideFromVpnReminder) {
            intent.putExtra(VpnReminderNotifier.EXTRA_OPENED_FROM_VPN_REMINDER, true)
        }
        if (this.intent.getBooleanExtra(EXTRA_VPN_REMINDER_CLICK_TRACKED, false)) {
            intent.putExtra(EXTRA_VPN_REMINDER_CLICK_TRACKED, true)
        }
        pendingOpenUri?.let { intent.data = it }
        pendingNewsShowType?.let { type ->
            intent.putExtra(HintUtil.CO_SHOW_TYPE, type)
            intent.putExtra(HintUtil.CO_NOTIFICATION_ID, pendingNewsNotificationId ?: -1)
        }
        setIntent(intent)
        dismissVpnReminderIfOpened(intent)
        if (intent.getBooleanExtra(EXTRA_HOT_START, false)) {
            restartSplashFlow()
        }
    }

    private fun restartSplashFlow() {
        splashSession++
        clearStartupAdTimeout()
        hasNavigatedToMain = false
        startupAdFlowStarted = false
        startupAdFlowCompleted = false
        startupAdShowing = false
        startupOpenAdState = StartupAdLoadState.NOT_STARTED
        startupGuide1AdState = StartupAdLoadState.NOT_STARTED
        startupGuide2AdState = StartupAdLoadState.NOT_STARTED
        binding.progressIndicator.visibility = View.VISIBLE
        if (umpFlowCompleted) {
            startStartupOpenAdFlow()
            requestStartupNotificationPermission()
        }
    }

    override fun initViews(savedInstanceState: Bundle?) {
        trackNewsNotificationClickIfNeeded(intent)
        trackLoadingShow(intent)
        rememberNotificationLaunch(intent)
        dismissVpnReminderIfOpened(intent)
        setupImmersiveStatusBar()
        onBackPressedDispatcher.addCallback{

        }
        showInitialProgress()
        startUmpFlow()
    }

    override fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shouldNavigateToMain.collect { shouldGo ->
                    if (shouldGo) navigateToMain()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回时再次校验（用户可能已设为默认浏览器）
        if (initialProgressCompleted && notificationPermissionResolved && !hasNavigatedToMain) {
            continueAfterInitialProgress()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarAppearance()
    }

    // ─────────────────────────── 权限过滤与全量校验 ───────────────────────────

    /**
     * 遍历候选权限列表 → 过滤无需申请 / 已满足项 → 更新 UI 或跳转首页。
     */
    private fun resolveGuideState(navigateIfComplete: Boolean) {
        if (!initialProgressCompleted ||
            !notificationPermissionResolved ||
            hasNavigatedToMain ||
            isFinishing ||
            browserGuideInFlight
        ) return

        val statuses = collectRequirementStatuses()
        val pending = viewModel.refreshFromStatuses(statuses)

        // 需求 3：已是默认浏览器且无其它待办时，直接进首页
        // （refreshFromStatuses 在 pending 为空时已置 shouldNavigateToMain）
        if (pending.isEmpty()) {
            if (navigateIfComplete) {
                navigateToMain()
            }
            return
        }

        if (pending.first() == GuideStep.BROWSER) launchDefaultBrowserGuide()
    }

    /** 构建完整候选状态列表（含适用性与是否已满足）。 */
    private fun showInitialProgress() {
        binding.progressIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            delay(INITIAL_PROGRESS_MIN_DURATION_MS.milliseconds)
            if (isFinishing || isDestroyed) return@launch
            initialProgressCompleted = true
            continueAfterInitialProgress()
        }
    }

    private fun requestStartupNotificationPermission() {
        if (!umpFlowCompleted) return
        if (!GuideViewModel.isNotificationApplicable()) {
            VpnPermissionHelper.trackLegacyAutoAgreeIfNeeded(this)
            completeNotificationPermissionStep()
            return
        }
        if (isNotificationPermissionGranted() ||
            !VpnPermissionHelper.canShowSystemPermissionDialog(this)
        ) {
            completeNotificationPermissionStep()
            return
        }
        if (notificationPermissionRequestInFlight) return
        if (notificationPermissionRequestPending) return
        notificationPermissionRequestPending = true
        lifecycleScope.launch {
            lifecycle.withResumed {
                notificationPermissionRequestPending = false
                if (isNotificationPermissionGranted() ||
                    !VpnPermissionHelper.canShowSystemPermissionDialog(this@GuideActivity)
                ) {
                    completeNotificationPermissionStep()
                    return@withResumed
                }
                notificationPermissionRequestInFlight = true
                VpnPermissionHelper.markNotificationPermissionRequested(this@GuideActivity)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun completeNotificationPermissionStep() {
        if (notificationPermissionResolved) return
        notificationPermissionResolved = true
        viewModel.dismissStep(GuideStep.NOTIFICATION)
        startStartupAdTimeout()
        updateStartupAdFlow()
        continueAfterInitialProgress()
    }

    private fun startUmpFlow() {
        if (umpFlowStarted) return
        umpFlowStarted = true
        if (getSharedPreferences(PREFS_UMP, MODE_PRIVATE).getBoolean(KEY_UMP_COMPLETED, false)) {
            finishUmpFlow()
            return
        }

        val debugSettings = ConsentDebugSettings.Builder(this)
            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            .addTestDeviceHashedId("65363D8B2D35B2D3B4361B43C8E86327")
            .build()
        val parameters = ConsentRequestParameters.Builder()
            .setConsentDebugSettings(debugSettings)
            .build()
        val consentInformation = UserMessagingPlatform.getConsentInformation(this)
        consentInformation.requestConsentInfoUpdate(
            this,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) {
                    finishUmpFlow()
                }
            },
            { error ->
                Log.w(TAG, "UMP request failed: ${error.message}")
                finishUmpFlow()
            },
        )
    }

    private fun finishUmpFlow() {
        if (umpFlowCompleted) return
        umpFlowCompleted = true
        getSharedPreferences(PREFS_UMP, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UMP_COMPLETED, true)
            .apply()
        startStartupOpenAdFlow()
        requestStartupNotificationPermission()
    }

    private fun continueAfterInitialProgress() {
        if (!initialProgressCompleted || !notificationPermissionResolved || !umpFlowCompleted) return
        updateStartupAdFlow()
        if (!startupAdFlowCompleted) return
        binding.progressIndicator.visibility = View.GONE
        resolveGuideState(navigateIfComplete = true)
    }

    private fun startStartupOpenAdFlow() {
        if (startupAdFlowStarted) return
        startupAdFlowStarted = true
        AdMobManager.runWhenInitialized { initialized ->
            if (!initialized || isFinishing || isDestroyed) {
                markAllStartupAdsUnavailable()
                return@runWhenInitialized
            }
            AdMobManager.resetGuideShowState()
            startupOpenAdState = StartupAdLoadState.LOADING
            startupGuide1AdState = StartupAdLoadState.LOADING
            startupGuide2AdState = StartupAdLoadState.LOADING
            AdMobManager.loadOpenAd(applicationContext, object : AdLoadListener {
                override fun onAdLoaded() {
                    scheduleHomeNativePreload()
                    startupOpenAdState = StartupAdLoadState.READY
                    updateStartupAdFlow()
                }

                override fun onAdFailedToLoad(error: String) {
                    scheduleHomeNativePreload()
                    startupOpenAdState = StartupAdLoadState.UNAVAILABLE
                    updateStartupAdFlow()
                }
            })
            startupAdHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                AdMobManager.loadInterstitial(applicationContext, AdMobManager.InterstitialPlacement.GUIDE_1, object : AdLoadListener {
                    override fun onAdLoaded() = updateStartupAdState(AdMobManager.InterstitialPlacement.GUIDE_1, StartupAdLoadState.READY)
                    override fun onAdFailedToLoad(error: String) = updateStartupAdState(AdMobManager.InterstitialPlacement.GUIDE_1, StartupAdLoadState.UNAVAILABLE)
                })
                AdMobManager.loadInterstitial(applicationContext, AdMobManager.InterstitialPlacement.GUIDE_2, object : AdLoadListener {
                    override fun onAdLoaded() = updateStartupAdState(AdMobManager.InterstitialPlacement.GUIDE_2, StartupAdLoadState.READY)
                    override fun onAdFailedToLoad(error: String) = updateStartupAdState(AdMobManager.InterstitialPlacement.GUIDE_2, StartupAdLoadState.UNAVAILABLE)
                })
                startupAdHandler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        preloadBannerForTargetPage()
                    }
                }, BANNER_LOAD_AFTER_GUIDE2_MS)
            }, GUIDE_INTERSTITIAL_LOAD_DELAY_MS)
        }
    }

    private fun scheduleHomeNativePreload() {
        if (homeNativePreloadScheduled) return
        homeNativePreloadScheduled = true
        AdMobManager.preloadNativeAd(
            applicationContext,
            AdMobManager.NativePlacement.HOME,
            HOME_NATIVE_PRELOAD_DELAY_MS,
        )
    }

    /**
     * 按即将进入的目标页预载 bar_banner：
     * - HomeFragment → 预载折叠轨（regular，离屏缓存可直接挂载）
     * - BrowserFragment → 不预载展开轨：AdMob collapsible 必须在可见 Activity 视图树上加载才会展开，
     *   Guide 离屏预载进入 Browser 只会显示成折叠态，改由 BrowserFragment 进页就地加载。
     */
    private fun preloadBannerForTargetPage() {
        if (willNavigateToBrowser()) return
        AdMobManager.loadBanner(applicationContext, AdMobManager.bannerModeRegular())
    }

    private fun willNavigateToBrowser(): Boolean {
        val scheme = pendingOpenUri?.scheme ?: return false
        return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
    }

    private fun updateStartupAdState(
        placement: AdMobManager.InterstitialPlacement,
        state: StartupAdLoadState,
    ) {
        when (placement) {
            AdMobManager.InterstitialPlacement.GUIDE_1 -> startupGuide1AdState = state
            AdMobManager.InterstitialPlacement.GUIDE_2 -> startupGuide2AdState = state
            else -> return
        }
        updateStartupAdFlow()
    }

    private fun markAllStartupAdsUnavailable() {
        startupOpenAdState = StartupAdLoadState.UNAVAILABLE
        startupGuide1AdState = StartupAdLoadState.UNAVAILABLE
        startupGuide2AdState = StartupAdLoadState.UNAVAILABLE
        updateStartupAdFlow()
    }

    private fun updateStartupAdFlow() {
        if (startupAdFlowCompleted || startupAdShowing || !notificationPermissionResolved ||
            !umpFlowCompleted ||
            isFinishing || isDestroyed
        ) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleScope.launch {
                lifecycle.withResumed { updateStartupAdFlow() }
            }
            return
        }
        if (!hasReadyStartupAd()) {
            if (allStartupAdsUnavailable()) finishStartupOpenAdFlow()
            else startStartupAdTimeout()
            return
        }
        val selectedAd = selectedStartupAd() ?: run {
            markReadyStartupAdsUnavailable()
            updateStartupAdFlow()
            return
        }
        var showFailureReported = false
        val session = splashSession
        val shown = AdMobManager.showGuideAdIfReady(this, object : AdShowListener {
            override fun onAdShowed() {
                if (session != splashSession) {
                    startupAdShowing = false
                    return
                }
                clearStartupAdTimeout()
            }

            override fun onAdShowFailed(error: String) {
                if (session != splashSession) {
                    startupAdShowing = false
                    return
                }
                showFailureReported = true
                startupAdShowing = false
                markStartupAdUnavailable(selectedAd)
                updateStartupAdFlow()
            }

            override fun onAdClosed() {
                startupAdShowing = false
                if (session != splashSession) return
                startupAdHandler.post {
                    if (session == splashSession && !hasNavigatedToMain) {
                        finishStartupOpenAdFlow()
                    }
                }
            }

            override fun onAdClicked() = Unit
        })
        if (shown) {
            startupAdShowing = true
            clearStartupAdTimeout()
        } else if (!showFailureReported && !AdMobManager.isAnyAdShowing()) {
            markStartupAdUnavailable(selectedAd)
            updateStartupAdFlow()
        }
    }

    private fun hasReadyStartupAd(): Boolean =
        startupOpenAdState == StartupAdLoadState.READY ||
            startupGuide1AdState == StartupAdLoadState.READY ||
            startupGuide2AdState == StartupAdLoadState.READY

    private fun allStartupAdsUnavailable(): Boolean =
        startupOpenAdState == StartupAdLoadState.UNAVAILABLE &&
            startupGuide1AdState == StartupAdLoadState.UNAVAILABLE &&
            startupGuide2AdState == StartupAdLoadState.UNAVAILABLE

    private fun markReadyStartupAdsUnavailable() {
        if (startupOpenAdState == StartupAdLoadState.READY) startupOpenAdState = StartupAdLoadState.UNAVAILABLE
        if (startupGuide1AdState == StartupAdLoadState.READY) startupGuide1AdState = StartupAdLoadState.UNAVAILABLE
        if (startupGuide2AdState == StartupAdLoadState.READY) startupGuide2AdState = StartupAdLoadState.UNAVAILABLE
    }

    private fun selectedStartupAd(): StartupAdSlot? = when {
        startupOpenAdState == StartupAdLoadState.READY && AdMobManager.isOpenAdLoaded() -> StartupAdSlot.OPEN
        startupGuide1AdState == StartupAdLoadState.READY &&
            AdMobManager.isInterstitialLoaded(AdMobManager.InterstitialPlacement.GUIDE_1) -> StartupAdSlot.GUIDE_1
        startupGuide2AdState == StartupAdLoadState.READY &&
            AdMobManager.isInterstitialLoaded(AdMobManager.InterstitialPlacement.GUIDE_2) -> StartupAdSlot.GUIDE_2
        else -> null
    }

    private fun markStartupAdUnavailable(ad: StartupAdSlot) {
        when (ad) {
            StartupAdSlot.OPEN -> startupOpenAdState = StartupAdLoadState.UNAVAILABLE
            StartupAdSlot.GUIDE_1 -> startupGuide1AdState = StartupAdLoadState.UNAVAILABLE
            StartupAdSlot.GUIDE_2 -> startupGuide2AdState = StartupAdLoadState.UNAVAILABLE
        }
    }

    private fun startStartupAdTimeout() {
        if (startupAdTimeout != null || startupAdShowing || startupAdFlowCompleted ||
            hasReadyStartupAd() || allStartupAdsUnavailable()
        ) return
        startupAdTimeout = Runnable { finishStartupOpenAdFlow() }.also {
            startupAdHandler.postDelayed(it, AdMobManager.getIntersOpenLoadTimeoutMs())
        }
    }

    private fun finishStartupOpenAdFlow() {
        if (startupAdFlowCompleted || startupAdShowing) return
        startupAdFlowCompleted = true
        clearStartupAdTimeout()
        continueAfterInitialProgress()
    }

    override fun onDestroy() {
        clearStartupAdTimeout()
        super.onDestroy()
    }

    private fun clearStartupAdTimeout() {
        startupAdTimeout?.let(startupAdHandler::removeCallbacks)
        startupAdTimeout = null
    }

    private enum class StartupAdLoadState { NOT_STARTED, LOADING, READY, UNAVAILABLE }
    private enum class StartupAdSlot { OPEN, GUIDE_1, GUIDE_2 }

    private fun collectRequirementStatuses(): List<GuideRequirementStatus> = listOf(
        GuideRequirementStatus(
            step = GuideStep.NOTIFICATION,
            applicable = GuideViewModel.isNotificationApplicable(),
            satisfied = isNotificationPermissionGranted(),
        ),
        GuideRequirementStatus(
            step = GuideStep.BROWSER,
            applicable = BrowserGuideControl.shouldShowDuringOnboarding(this),
            satisfied = false,
        ),
    )

    private fun isNotificationPermissionGranted(): Boolean {
        if (!GuideViewModel.isNotificationApplicable()) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun dismissVpnReminderIfOpened(intent: Intent) {
        VpnReminderNotifier.dismissIfOpenedFromReminder(intent)
        if (!intent.getBooleanExtra(EXTRA_VPN_REMINDER_CLICK_TRACKED, false) &&
            intent.getBooleanExtra(VpnReminderNotifier.EXTRA_OPENED_FROM_VPN_REMINDER, false)
        ) {
            intent.putExtra(EXTRA_VPN_REMINDER_CLICK_TRACKED, true)
            UpDataTool.trackEvent("vpn_notification_click")
        }
    }

    /**
     * 通知点击后尽早上报 news_notification_click：在启动页 onCreate/onNewIntent 收到点击 Intent 时
     * 立即上报，不再等待开屏与广告流程跳转到内容页。覆盖新闻通知（CO_SHOW_TYPE）与常驻通知栏
     * （EXTRA_FROM_VPN_BAR）两个来源，并用 extra 标记去重，保证一次点击仅上报一次。
     *
     * 说明：Android 12+ 禁止通知点击经由广播/服务再拉起 Activity（trampoline），启动页 onCreate
     * 即为"点击即达"的最早合法上报点。
     */
    private fun trackNewsNotificationClickIfNeeded(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_NEWS_CLICK_TRACKED, false)) return
        val fromNews = intent.hasExtra(HintUtil.CO_SHOW_TYPE)
        val fromVpnBar = intent.getBooleanExtra(EXTRA_FROM_VPN_BAR, false)
        if (!fromNews && !fromVpnBar) return
        intent.putExtra(EXTRA_NEWS_CLICK_TRACKED, true)
        runCatching { UpDataTool.trackEvent("news_notification_click") }
            .onFailure { Log.w(TAG, "news_notification_click failed: ${it.message}", it) }
    }

    private fun launchDefaultBrowserGuide() {
        if (browserGuideInFlight || hasNavigatedToMain || isFinishing) return
        browserGuideInFlight = true
        defaultBrowserGuideLauncher.launch(
            DefaultBrowserGuideActivity.createIntent(this, blocking = true),
        )
    }

    private fun trackLoadingShow(intent: Intent) {
        runCatching {
            val source = LoadingTracking.resolveSource(
                fromFcm = intent.getBooleanExtra(LoadingTracking.EXTRA_FROM_FCM, false),
                fromSystemNotification = intent.getBooleanExtra(
                    LoadingTracking.EXTRA_FROM_SYSTEM,
                    false,
                ) ||
                    intent.getBooleanExtra(
                        VpnReminderNotifier.EXTRA_OPENED_FROM_VPN_REMINDER,
                        false,
                    ) ||
                    intent.hasExtra(HintUtil.CO_SHOW_TYPE),
            )
            UpDataTool.trackEvent(
                LoadingTracking.EVENT,
                LoadingTracking.contextParams(
                    source = source,
                    firstOpen = LoadingTracking.consumeIsFirstOpen(this),
                ),
            )
        }.onFailure {
            Log.w(TAG, "loading_show failed: ${it.message}", it)
        }
    }

    private fun rememberNotificationLaunch(intent: Intent) {
        if (intent.getBooleanExtra(VpnReminderNotifier.EXTRA_OPENED_FROM_VPN_REMINDER, false)) {
            openAfterGuideFromVpnReminder = true
        }
        intent.data?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
            ?.let { pendingOpenUri = it }
        if (intent.hasExtra(HintUtil.CO_SHOW_TYPE)) {
            pendingNewsShowType = intent.getIntExtra(HintUtil.CO_SHOW_TYPE, -1)
            pendingNewsNotificationId = intent.getIntExtra(HintUtil.CO_NOTIFICATION_ID, -1)
        }
    }

    private fun navigateToMain() {
        if (hasNavigatedToMain || isFinishing) return
        hasNavigatedToMain = true
        if (LeadStore.shouldShow(this)) {
            startActivity(
                LeadActivity.createIntent(
                    context = this,
                    openUri = pendingOpenUri,
                    newsShowType = pendingNewsShowType,
                    newsNotificationId = pendingNewsNotificationId,
                    openFromVpnReminder = openAfterGuideFromVpnReminder,
                ),
            )
            finish()
            return
        }

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            pendingOpenUri?.let {
                action = Intent.ACTION_VIEW
                data = it
            }
            pendingNewsShowType?.let { type ->
                putExtra(HintUtil.CO_SHOW_TYPE, type)
                putExtra(HintUtil.CO_NOTIFICATION_ID, pendingNewsNotificationId ?: -1)
            }
        }
        startActivity(mainIntent)
        if (openAfterGuideFromVpnReminder) {
            startActivity(Intent(this, VpnActivity::class.java))
        }
        finish()
    }

    // ─────────────────────────── 沉浸式状态栏 ───────────────────────────

    /**
     * 沉浸式实现：
     * - 全版本 edge-to-edge + 透明状态栏/导航栏
     * - 按背景明暗自动切换状态栏图标颜色（浅色背景用深色图标）
     */
    private fun setupImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        applyStatusBarAppearance()

        ViewCompat.setOnApplyWindowInsetsListener(binding.guide) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.guide.updatePadding(left = systemBars.left, right = systemBars.right)
            insets
        }
        ViewCompat.requestApplyInsets(binding.guide)
    }

    /**
     * 设置状态栏/导航栏图标颜色，保证浅色背景下可读。
     * 引导页背景为浅色渐变，使用深色状态栏图标（appearanceLightStatusBars = true）。
     * 若后续替换为深色背景图，将 [lightBackground] 改为 false 即可。
     */
    private fun applyStatusBarAppearance(lightBackground: Boolean = true) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightBackground
        controller.isAppearanceLightNavigationBars = lightBackground
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        private const val TAG = "GuideActivity"
        const val INITIAL_PROGRESS_MIN_DURATION_MS = 2_000L
        const val HOME_NATIVE_PRELOAD_DELAY_MS = 2_000L
        private const val GUIDE_INTERSTITIAL_LOAD_DELAY_MS = 500L
        private const val BANNER_LOAD_AFTER_GUIDE2_MS = 500L
        const val EXTRA_HOT_START = "extra_hot_start"
        private const val EXTRA_VPN_REMINDER_CLICK_TRACKED = "extra_vpn_reminder_click_tracked"
        /** 常驻通知栏点击来源标记：由 [com.anony.bro.wser.vpn.AnonyBrowserVpnService] 写入。 */
        const val EXTRA_FROM_VPN_BAR = "extra_from_vpn_bar"
        private const val EXTRA_NEWS_CLICK_TRACKED = "extra_news_click_tracked"

        fun createHotStartIntent(context: Context): Intent =
            Intent(context, GuideActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_HOT_START, true)
            }
        private const val PREFS_UMP = "ump"
        private const val KEY_UMP_COMPLETED = "completed"
    }
}
