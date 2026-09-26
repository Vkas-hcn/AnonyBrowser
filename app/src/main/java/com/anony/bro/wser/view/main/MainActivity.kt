package com.anony.bro.wser.view.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.anony.bro.wser.R
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.base.BaseActivity
import com.anony.bro.wser.base.BaseViewModel
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.ads.AdShowListener
import com.anony.bro.wser.ads.HomeInterstitialPolicy
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.history.HistoryRepository
import com.anony.bro.wser.data.settings.BrowserSettingsStore
import com.anony.bro.wser.data.tab.PersistedTab
import com.anony.bro.wser.data.tab.TabLoadState
import com.anony.bro.wser.data.tab.TabSessionRepository
import com.anony.bro.wser.data.tab.TabSessionSnapshot
import com.anony.bro.wser.databinding.ActivityMainBinding
import com.anony.bro.wser.databinding.BottomSheetNotificationPermissionBinding
import com.anony.bro.wser.databinding.PopupBrowserMenuBinding
import com.anony.bro.wser.guide.BrowserGuideControl
import com.anony.bro.wser.hellohello.HintUtil
import com.anony.bro.wser.hellohello.NoticeConfigStore
import com.anony.bro.wser.view.main.MainActivity.Companion.STATE_SELECTED_TAB
import com.anony.bro.wser.view.settings.SettingsActivity
import com.anony.bro.wser.view.guide.DefaultBrowserGuideActivity
import com.anony.bro.wser.view.vpn.VpnActivity
import com.anony.bro.wser.vpn.VpnManager
import com.anony.bro.wser.vpn.VpnPermissionHelper
import java.util.UUID

class MainViewModel : BaseViewModel()

class MainActivity :
    BaseActivity<ActivityMainBinding, MainViewModel>(),
    HomeFragment.Callback,
    BrowserFragment.Callback {

    override val viewModel: MainViewModel by viewModels()
    override val edgeToEdgeEnabled: Boolean = true
    override val applySystemBarPadding: Boolean = false

    private val tabs = mutableListOf<BrowserTab>()
    private var selectedTabId: String? = null
    private var isShowingHome = true
    private var bottomNavBaseHeight = 0
    private var lastStatusBarInset = 0
    private var lastNavBarInset = 0
    private var pendingSearchResultTabId: String? = null
    private var notificationSettingsGuideHandled = false
    private var notificationSettingsGuide: BottomSheetDialog? = null
    private lateinit var homeFragment: HomeFragment
    private val tabSessionRepository by lazy { TabSessionRepository.get(this) }
    private val historyRepository by lazy { HistoryRepository.get(this) }
    private val lastHistorySignatures = mutableMapOf<String, String>()
    private val tabListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let(::applyTabListResult)
        }
    }
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let(::applyHistoryResult)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityMainBinding =
        ActivityMainBinding.inflate(inflater)

    override fun initViews(savedInstanceState: Bundle?) {
        bottomNavBaseHeight = binding.bottomNav.layoutParams.height
        setupSystemBars()
        setupFragments(savedInstanceState)
        setupBottomNav()
        setupBackHandling()
        restoreTabs(savedInstanceState)
        handleIntent(intent)
        updateBottomNavState()
        // 等首帧布局后再走评分 / 通知设置引导
        binding.main.post { startHomeGuideSequence() }
    }

    override fun observeData() = Unit

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (VpnManager.handleActivityResult(requestCode, resultCode)) {
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (VpnManager.handlePermissionsResult(this, requestCode, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_TAB, selectedTabId)
        outState.putStringArrayList(STATE_TAB_IDS, ArrayList(tabs.map { it.id }))
        outState.putStringArrayList(STATE_TAB_TITLES, ArrayList(tabs.map { it.title }))
        outState.putStringArrayList(STATE_TAB_URLS, ArrayList(tabs.map { it.url.orEmpty() }))
        outState.putBooleanArray(STATE_TAB_LOADING, tabs.map { it.loadState.isLoading }.toBooleanArray())
        outState.putStringArrayList(
            STATE_TAB_LOAD_STATES,
            ArrayList(tabs.map { it.loadState.name }),
        )
        outState.putBoolean(STATE_SHOWING_HOME, isShowingHome)
        persistTabsAsync()
    }

    override fun onStop() {
        persistTabsBlocking()
        super.onStop()
    }

    override fun onDestroy() {
        notificationSettingsGuide?.dismiss()
        notificationSettingsGuide = null
        persistTabsAsync()
        AdMobManager.releaseBannerHost(binding.bannerOverlay)
        super.onDestroy()
    }

    override fun onHomeOpenUrl(url: String, isSearch: Boolean) {
        if (isSearch && BrowserGuideControl.shouldShowAfterFirstSearch(this)) {
            pendingSearchResultTabId = currentTab()?.id ?: createBlankTab(select = true).id
        }
        openUrlInCurrentTab(url)
        AdMobManager.loadInterstitial(applicationContext, AdMobManager.InterstitialPlacement.BACK)
    }

    override fun onRateDialogDismissed() {
        binding.main.post { continueHomeGuidesAfterRate() }
    }

    override fun canLoadHomeNativeAd(): Boolean = isShowingHome

    fun bannerOverlay(): ViewGroup = binding.bannerOverlay

    override fun onBrowserStateChanged(
        tabId: String,
        title: String?,
        url: String?,
        progress: Int,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
        hasError: Boolean,
    ) {
        tabs.firstOrNull { it.id == tabId }?.let { tab ->
            val wasLoading = tab.loadState.isLoading
            tab.title = if (url.isNullOrBlank()) {
                getString(R.string.browser_new_tab)
            } else {
                title?.takeIf { it.isNotBlank() } ?: url
            }
            tab.url = url
            tab.canGoBack = canGoBack
            tab.canGoForward = canGoForward
            tab.loadState = TabLoadState.from(
                isLoading = isLoading,
                hasError = hasError,
                hasUrl = !url.isNullOrBlank(),
            )
            viewModel.onBrowserPageStateSync(tabId, url, tab.title, progress)
            // 仅在一次加载完成时写入历史，避免同 URL 重复追加；不覆盖已有记录
            if (wasLoading && !isLoading && !hasError && !url.isNullOrBlank()) {
                recordHistory(tab)
            }
        }
        if (selectedTabId == tabId && !isShowingHome) {
            updateBottomNavState()
        }
        if (tabId == pendingSearchResultTabId && !isLoading) {
            pendingSearchResultTabId = null
            if (!hasError && BrowserGuideControl.shouldShowAfterFirstSearch(this)) {
                startActivity(DefaultBrowserGuideActivity.createIntent(this, blocking = false))
            }
        }
        persistTabsAsync()
    }

    private fun recordHistory(tab: BrowserTab) {
        if (BrowserSettingsStore.isSeamlessEnabled(this)) return
        val url = tab.url?.takeIf { it.isNotBlank() } ?: return
        if (url.startsWith("about:", ignoreCase = true)) return
        // 同一 Tab 对同一 URL 的同一次打开只记一条
        if (lastHistorySignatures[tab.id] == url) return
        lastHistorySignatures[tab.id] = url
        val faviconPath = tab.fragment?.captureFavicon(cacheDir, tab.id)
        historyRepository.addAsync(
            title = tab.title,
            url = url,
            faviconPath = faviconPath,
        )
    }

    private fun setupSystemBars() {
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
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isShowingHome
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            lastStatusBarInset = systemBars.top
            lastNavBarInset = systemBars.bottom
            binding.browserContainer.updatePadding(
                left = systemBars.left,
                top = 0,
                right = systemBars.right,
                bottom = if (isShowingHome) 0 else systemBars.bottom,
            )
            binding.bottomNav.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = bottomNavBaseHeight + systemBars.bottom
            }
            binding.bottomNav.updatePadding(bottom = systemBars.bottom)
            if (::homeFragment.isInitialized) {
                homeFragment.setContentInsets(systemBars.top, 0)
            }
            applyBrowserContentInsets()
            insets
        }
        ViewCompat.requestApplyInsets(binding.main)
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val existingHome = supportFragmentManager.findFragmentByTag(HOME_TAG) as? HomeFragment
        homeFragment = existingHome ?: HomeFragment()
        if (existingHome == null) {
            supportFragmentManager.beginTransaction()
                .add(binding.browserContainer.id, homeFragment, HOME_TAG)
                .commitNow()
        }
        // 冷启动不在此处创建空白 Tab：由 restoreTabs() 从本地会话恢复或降级创建
        if (savedInstanceState != null) {
            showHome()
        }
    }

    private fun restoreTabs(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            restoreTabsFromBundle(savedInstanceState)
            return
        }
        restoreTabsFromDisk()
    }

    private fun restoreTabsFromBundle(savedInstanceState: Bundle) {
        tabs.clear()
        val ids = savedInstanceState.getStringArrayList(STATE_TAB_IDS).orEmpty()
        val titles = savedInstanceState.getStringArrayList(STATE_TAB_TITLES).orEmpty()
        val urls = savedInstanceState.getStringArrayList(STATE_TAB_URLS).orEmpty()
        val loadStates = savedInstanceState.getStringArrayList(STATE_TAB_LOAD_STATES)
        val loadings = savedInstanceState.getBooleanArray(STATE_TAB_LOADING) ?: BooleanArray(ids.size)
        ids.forEachIndexed { index, id ->
            val url = urls.getOrNull(index)?.takeIf { it.isNotBlank() }
            val loadState = loadStates?.getOrNull(index)?.let(TabLoadState::parse)
                ?: TabLoadState.from(
                    isLoading = loadings.getOrNull(index) == true,
                    hasError = false,
                    hasUrl = url != null,
                )
            tabs += BrowserTab(
                id = id,
                title = if (url == null) {
                    getString(R.string.browser_new_tab)
                } else {
                    titles.getOrNull(index)?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.browser_new_tab)
                },
                url = url,
                loadState = loadState,
                fragment = supportFragmentManager.findFragmentByTag(tabTag(id)) as? BrowserFragment,
            )
        }
        selectedTabId = savedInstanceState.getString(STATE_SELECTED_TAB)
        if (tabs.isEmpty()) {
            createBlankTab(select = true)
            return
        }
        val shouldShowHome = savedInstanceState.getBoolean(STATE_SHOWING_HOME, true)
        val selected = currentTab()
        if (!shouldShowHome && selected?.url != null) {
            ensureTabFragment(selected)
            showBrowser(selected)
        } else {
            showHome()
        }
    }

    private fun restoreTabsFromDisk() {
        val snapshot = runCatching { tabSessionRepository.loadBlocking() }
            .onFailure { android.util.Log.w(TAG, "Load tab session failed, using default", it) }
            .getOrDefault(TabSessionSnapshot.EMPTY)

        if (snapshot.tabs.isEmpty()) {
            createBlankTab(select = true)
            return
        }

        tabs.clear()
        snapshot.tabs.forEach { persisted ->
            val title = if (persisted.url == null) {
                getString(R.string.browser_new_tab)
            } else {
                persisted.title.takeIf { it.isNotBlank() }
                    ?: getString(R.string.browser_new_tab)
            }
            // 冷启动时 LOADING 保留以便重新加载；无 URL 则降为 IDLE
            val restoredLoadState = when {
                persisted.url == null -> TabLoadState.IDLE
                persisted.loadState == TabLoadState.LOADING -> TabLoadState.LOADING
                else -> persisted.loadState
            }
            tabs += BrowserTab(
                id = persisted.id,
                title = title,
                url = persisted.url,
                loadState = restoredLoadState,
                fragment = null,
            )
        }

        selectedTabId = resolveSelectedTabId(snapshot)
        if (tabs.isEmpty()) {
            createBlankTab(select = true)
            return
        }

        val selected = currentTab() ?: tabs.first().also { selectedTabId = it.id }
        if (!snapshot.showingHome && selected.url != null) {
            ensureTabFragment(selected)
            showBrowser(selected)
        } else {
            showHome()
        }
        updateBottomNavState()
    }

    private fun resolveSelectedTabId(snapshot: TabSessionSnapshot): String? {
        snapshot.selectedTabId
            ?.takeIf { id -> tabs.any { it.id == id } }
            ?.let { return it }
        val index = snapshot.selectedTabIndex
        if (index in tabs.indices) return tabs[index].id
        return tabs.firstOrNull()?.id
    }

    private fun ensureTabFragment(tab: BrowserTab) {
        if (tab.fragment != null) return
        val url = tab.url ?: return
        tab.fragment = BrowserFragment.newInstance(tab.id, url)
    }

    private fun setupBottomNav() {
        binding.btnBack.setOnClickListener {
            val browser = currentBrowserFragment()
            when {
                browser?.canGoBack() == true -> showHomeInterstitialThen { browser.goBack() }
                !isShowingHome -> returnToHomeWithAd()
            }
        }
        binding.btnForward.setOnClickListener {
            showHomeInterstitialThen { if (isShowingHome) resumeCurrentTabPage() else currentBrowserFragment()?.goForward() }
        }
        binding.btnVpn.setOnClickListener {
            showHomeInterstitialThen { startActivity(Intent(this, VpnActivity::class.java)) }
        }
        binding.btnTabs.setOnClickListener {
            showHomeInterstitialThen { openTabListPage() }
        }
        binding.btnMenu.setOnClickListener {
            showHomeInterstitialThen { showBrowserMenu(anchor = binding.btnMenu, dropDown = false) }
        }
    }

    private fun startHomeGuideSequence() {
        if (!isShowingHome) {
            continueHomeGuidesAfterRate()
            return
        }
        homeFragment.showRateDialogIfNeeded()
        if (!homeFragment.isRateDialogShowing()) {
            continueHomeGuidesAfterRate()
        }
    }

    private fun continueHomeGuidesAfterRate() {
        showNotificationSettingsGuideIfNeeded()
    }

    private fun showNotificationSettingsGuideIfNeeded() {
        if (notificationSettingsGuideHandled ||
            isFinishing ||
            isDestroyed ||
            homeFragment.isRateDialogShowing() ||
            notificationSettingsGuide?.isShowing == true ||
            !VpnPermissionHelper.shouldShowNotificationSettingsGuide(this)
        ) return

        notificationSettingsGuideHandled = true
        val dialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetNotificationPermissionBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        dialog.setCancelable(true)
        dialog.behavior.isDraggable = true
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        sheetBinding.btnConfirm.setOnClickListener {
            dialog.dismiss()
            if (VpnPermissionHelper.openNotificationSettings(this)) {
                GateBrowserApplication.get().skipNextHotStart()
            }
        }
        notificationSettingsGuide = dialog
        dialog.setOnDismissListener {
            if (notificationSettingsGuide === dialog) {
                notificationSettingsGuide = null
            }
        }
        dialog.show()
    }

    override fun onBrowserTabsClick() {
        openTabListPage()
    }

    override fun onBrowserMenuClick(anchor: View) {
        showBrowserMenu(anchor = anchor, dropDown = true)
    }

    override fun onBrowserReturnHome() {
        returnToHomeWithAd()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val browser = currentBrowserFragment()
                when {
                    !isShowingHome && browser?.canGoBack() == true -> browser.goBack()
                    !isShowingHome -> returnToHomeWithAd()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(HintUtil.CO_SHOW_TYPE)) {
            val showType = intent.getIntExtra(HintUtil.CO_SHOW_TYPE, -1)
            if (showType >= 0) {
                HintUtil.cancelNewsNotification(
                    showType,
                    intent.getIntExtra(HintUtil.CO_NOTIFICATION_ID, -1).takeIf { it > 0 },
                )
            }
            intent.removeExtra(HintUtil.CO_SHOW_TYPE)
            intent.removeExtra(HintUtil.CO_NOTIFICATION_ID)
            UpDataTool.trackEvent("news_notification_click")
        }
        if (intent.getBooleanExtra(EXTRA_RESET_BROWSER, false)) {
            intent.removeExtra(EXTRA_RESET_BROWSER)
            performBrowserReset()
            return
        }
        val uri = intent.data ?: return
        if (intent.action == Intent.ACTION_VIEW && uri.scheme in setOf("http", "https")) {
            openUrlInCurrentTab(uri.toString())
            AdMobManager.loadInterstitial(applicationContext, AdMobManager.InterstitialPlacement.BACK)
        }
    }

    private fun performBrowserReset() {
        tabs.toList().forEach(::removeTabFragment)
        tabs.clear()
        selectedTabId = null
        lastHistorySignatures.clear()
        createBlankTab(select = true)
        persistTabsBlocking()
        if (::homeFragment.isInitialized) {
            homeFragment.onBrowserDataReset()
        }
        showHome()
    }

    private fun openUrlInCurrentTab(url: String) {
        val tab = currentTab() ?: createBlankTab(select = true)
        tab.url = url
        tab.title = url
        tab.loadState = TabLoadState.LOADING
        // 从首页发起的跳转视为全新打开：重建 Fragment，避免先露出上一页再加载
        if (isShowingHome) {
            openUrlAsFreshPage(tab, url)
            return
        }
        val fragment = tab.fragment ?: BrowserFragment.newInstance(tab.id, url).also {
            tab.fragment = it
        }
        showBrowser(tab, fragment)
        fragment.loadUrl(url)
        persistTabsAsync()
    }

    private fun openUrlAsFreshPage(tab: BrowserTab, url: String) {
        tab.fragment?.takeIf { it.isAdded }?.let { old ->
            supportFragmentManager.beginTransaction()
                .remove(old)
                .commitNow()
        }
        tab.fragment = null
        tab.canGoBack = false
        tab.canGoForward = false
        tab.loadState = TabLoadState.LOADING
        val fragment = BrowserFragment.newInstance(tab.id, url).also {
            tab.fragment = it
        }
        showBrowser(tab, fragment)
        persistTabsAsync()
    }

    private fun showHome() {
        isShowingHome = true
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments
            .filter { it != homeFragment && it.isAdded }
            .forEach(transaction::hide)
        transaction.show(homeFragment).commitNow()
        applyContainerBottomInset()
        ViewCompat.requestApplyInsets(binding.main)
        applyStatusBarAppearance()
        updateBottomNavState()
    }

    private fun returnToHomeWithAd() {
        if (!NoticeConfigStore.browserInterstitialEnabled()) { showHome(); return }
        var completed = false
        fun complete() { if (!completed) { completed = true; showHome() } }
        val shown = AdMobManager.showInterstitial(this, AdMobManager.InterstitialPlacement.BACK, object : AdShowListener {
            override fun onAdShowed() = Unit
            override fun onAdShowFailed(error: String) = complete()
            override fun onAdClosed() = complete()
            override fun onAdClicked() = Unit
        })
        if (!shown) complete()
    }

    private fun showHomeInterstitialThen(action: () -> Unit) {
        if (!isShowingHome) {
            action()
            return
        }
        val config = NoticeConfigStore.homeInterstitialConfig()
        if (!HomeInterstitialPolicy.canAttempt(config.enabled)) { action(); return }
        var completed = false
        var didShow = false
        fun complete() {
            if (!didShow) HomeInterstitialPolicy.releaseAttempt()
            if (!completed) {
                completed = true
                action()
            }
        }
        val shown = AdMobManager.showInterstitial(this, AdMobManager.InterstitialPlacement.HOME, object : AdShowListener {
            override fun onAdShowed() {
                didShow = true
                HomeInterstitialPolicy.onShown(config.interval)
            }
            override fun onAdShowFailed(error: String) = complete()
            override fun onAdClosed() = complete()
            override fun onAdClicked() = Unit
        })
        if (!shown) complete()
    }

    private fun showBrowser(tab: BrowserTab, fragment: BrowserFragment? = tab.fragment) {
        val browserFragment = fragment ?: return showHome()
        isShowingHome = false
        selectedTabId = tab.id
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments
            .filter { it.isAdded }
            .forEach(transaction::hide)
        if (!browserFragment.isAdded) {
            transaction.add(binding.browserContainer.id, browserFragment, tabTag(tab.id))
        }
        transaction.show(browserFragment).commitNow()
        applyContainerBottomInset()
        applyBrowserContentInsets()
        ViewCompat.requestApplyInsets(binding.main)
        applyStatusBarAppearance()
        updateBottomNavState()
    }

    private fun applyStatusBarAppearance() {
        @Suppress("DEPRECATION")
        window.statusBarColor = if (isShowingHome) Color.TRANSPARENT else Color.WHITE
        binding.browserContainer.setBackgroundColor(
            if (isShowingHome) Color.TRANSPARENT else Color.WHITE,
        )
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // 首页蓝底用浅色图标；网页白顶栏用深色图标
            isAppearanceLightStatusBars = !isShowingHome
            isAppearanceLightNavigationBars = true
        }
    }

    private fun createBlankTab(select: Boolean): BrowserTab {
        val tab = BrowserTab(
            id = UUID.randomUUID().toString(),
            title = getString(R.string.browser_new_tab),
            loadState = TabLoadState.IDLE,
        )
        tabs += tab
        if (select) {
            selectedTabId = tab.id
            showHome()
        }
        updateBottomNavState()
        persistTabsAsync()
        return tab
    }

    private fun selectTab(tabId: String) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        selectedTabId = tab.id
        openTabPage(tab)
        persistTabsAsync()
    }

    /** 有 URL 则确保 Fragment 并打开网页；否则回首页。 */
    private fun openTabPage(tab: BrowserTab) {
        val url = tab.url?.takeIf { it.isNotBlank() }
        if (url == null) {
            showHome()
            return
        }
        ensureTabFragment(tab)
        showBrowser(tab)
    }

    private fun closeTab(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return
        val removed = tabs.removeAt(index)
        removeTabFragment(removed)
        if (tabs.isEmpty()) {
            selectedTabId = null
            showHome()
            updateBottomNavState()
            persistTabsAsync()
            return
        }
        if (selectedTabId == tabId) {
            val nextIndex = index.coerceAtMost(tabs.lastIndex)
            selectTab(tabs[nextIndex].id)
        } else {
            updateBottomNavState()
            persistTabsAsync()
        }
    }

    private fun applyHistoryResult(data: Intent) {
        val url = HistoryActivity.readUrl(data) ?: return
        if (HistoryActivity.shouldOpenInNewTab(data)) {
            // 新建 Tab 打开，不影响其它 Tab，也不删除任何历史记录
            val tab = createBlankTab(select = false)
            selectedTabId = tab.id
            openUrlAsFreshPage(tab, url)
        } else {
            openUrlInCurrentTab(url)
        }
    }

    private fun openTabListPage() {
        persistTabsAsync()
        val intent = TabListActivity.createIntent(
            context = this,
            ids = ArrayList(tabs.map { it.id }),
            titles = ArrayList(tabs.map { it.title }),
            urls = ArrayList(tabs.map { it.url.orEmpty() }),
            loadStates = ArrayList(tabs.map { it.loadState.name }),
            thumbnails = ArrayList(tabs.map { tab ->
                tab.fragment?.captureThumbnail(cacheDir, tab.id).orEmpty()
            }),
            favicons = ArrayList(tabs.map { tab ->
                tab.fragment?.captureFavicon(cacheDir, tab.id).orEmpty()
            }),
            selectedTabId = selectedTabId,
        )
        tabListLauncher.launch(intent)
    }

    private fun applyTabListResult(data: Intent) {
        val resultIds = TabListActivity.readIds(data)
        val resultTitles = TabListActivity.readTitles(data)
        val resultUrls = TabListActivity.readUrls(data)
        val resultLoadStates = TabListActivity.readLoadStates(data)
        val previousSelectedId = selectedTabId
        val resultIdSet = resultIds.toSet()

        tabs.filter { it.id !in resultIdSet }
            .toList()
            .forEach { removed ->
                removeTabFragment(removed)
                tabs.remove(removed)
            }

        resultIds.forEachIndexed { index, id ->
            val url = resultUrls.getOrNull(index)?.takeIf { it.isNotBlank() }
            val title = if (url == null) {
                getString(R.string.browser_new_tab)
            } else {
                resultTitles.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.browser_new_tab)
            }
            val loadState = TabLoadState.parse(resultLoadStates.getOrNull(index))
            val existing = tabs.firstOrNull { it.id == id }
            if (existing == null) {
                tabs += BrowserTab(
                    id = id,
                    title = title,
                    url = url,
                    loadState = loadState,
                    fragment = url?.let { BrowserFragment.newInstance(id, it) },
                )
            } else {
                existing.title = title
                existing.url = url
                existing.loadState = loadState
            }
        }

        val resultSelectedId = TabListActivity.readSelectedId(data)
            ?.takeIf { id -> tabs.any { it.id == id } }
        selectedTabId = when {
            TabListActivity.shouldOpenSelected(data) -> resultSelectedId
            previousSelectedId != null && tabs.any { it.id == previousSelectedId } -> previousSelectedId
            else -> resultSelectedId ?: tabs.firstOrNull()?.id
        }

        if (tabs.isEmpty()) {
            selectedTabId = null
            showHome()
            updateBottomNavState()
            persistTabsAsync()
            return
        }

        if (TabListActivity.shouldOpenSelected(data)) {
            selectedTabId?.let(::selectTab) ?: showHome()
        } else if (isShowingHome) {
            showHome()
        } else {
            currentTab()?.let(::openTabPage) ?: showHome()
        }
        updateBottomNavState()
        persistTabsAsync()
    }

    private fun removeTabFragment(tab: BrowserTab) {
        tab.fragment?.takeIf { it.isAdded }?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commitNow()
        }
        tab.fragment = null
    }

    private fun showTabsSheet() {
        val dialog = BottomSheetDialog(this)
        val panel = createSheetPanel()
        panel.addView(createSheetTitle(getString(R.string.browser_tabs)))
        tabs.forEach { tab ->
            panel.addView(createTabRow(dialog, tab))
        }
        panel.addView(createSheetAction(getString(R.string.browser_new_tab)) {
            createBlankTab(select = true)
            dialog.dismiss()
        })
        dialog.setContentView(panel)
        dialog.show()
    }

    private fun createTabRow(dialog: BottomSheetDialog, tab: BrowserTab): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            setBackgroundResource(R.drawable.bg_sheet_item)
            isClickable = true
            setOnClickListener {
                selectTab(tab.id)
                dialog.dismiss()
            }
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textColumn.addView(TextView(this).apply {
            val title = if (tab.url.isNullOrBlank()) {
                getString(R.string.browser_new_tab)
            } else {
                tab.title
            }
            text = if (tab.id == selectedTabId) "$title  *" else title
            textSize = 16f
            setTextColor(0xFF1E2630.toInt())
            maxLines = 1
        })
        textColumn.addView(TextView(this).apply {
            text = tab.url ?: getString(R.string.browser_no_page)
            textSize = 12f
            setTextColor(0xFF7B8491.toInt())
            maxLines = 1
        })
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close_tab)
            background = null
            contentDescription = getString(R.string.browser_close_tab)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                closeTab(tab.id)
                dialog.dismiss()
                showTabsSheet()
            }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        row.layoutParams = sheetItemLayoutParams()
        return row
    }

    private fun showBrowserMenu(anchor: View, dropDown: Boolean) {
        val menuBinding = PopupBrowserMenuBinding.inflate(layoutInflater)
        val popupWidth = dp(168)
        menuBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = menuBinding.root.measuredHeight
        val popup = PopupWindow(
            menuBinding.root,
            popupWidth,
            popupHeight,
            true,
        ).apply {
            elevation = dp(12).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isClippingEnabled = false
        }

        val current = currentTab()
        fun bindSeamlessLabel() {
            val on = BrowserSettingsStore.isSeamlessEnabled(this)
            menuBinding.tvSeamless.setText(
                if (on) R.string.browser_seamless_on else R.string.browser_seamless,
            )
        }
        bindSeamlessLabel()
        menuBinding.menuSeamless.setOnClickListener {
            val next = !BrowserSettingsStore.isSeamlessEnabled(this)
            BrowserSettingsStore.setSeamlessEnabled(this, next)
            bindSeamlessLabel()
            popup.dismiss()
        }
        menuBinding.menuRefresh.setOnClickListener {
            if (current?.loadState?.isLoading == true) {
                currentBrowserFragment()?.stopLoading()
            } else {
                currentBrowserFragment()?.reload()
            }
            popup.dismiss()
        }
        menuBinding.menuHistory.setOnClickListener {
            popup.dismiss()
            historyLauncher.launch(HistoryActivity.createIntent(this))
        }
        menuBinding.menuShare.setOnClickListener {
            shareCurrentPage()
            popup.dismiss()
        }
        menuBinding.menuCloseTab.setOnClickListener {
            current?.id?.let { closeTab(it) }
            popup.dismiss()
        }
        menuBinding.menuSettings.setOnClickListener {
            popup.dismiss()
            startActivity(SettingsActivity.createIntent(this))
        }

        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        // Gravity.END 时 x 表示距右边缘的距离
        val x = dp(21)
        val y = if (dropDown) {
            anchorLocation[1] + anchor.height + dp(4)
        } else {
            (anchorLocation[1] - popupHeight + dp(8)).coerceAtLeast(0)
        }
        popup.showAtLocation(binding.root, Gravity.TOP or Gravity.END, x, y)
    }

    private fun shareCurrentPage() {
        val url = currentTab()?.url
        if (url.isNullOrBlank()) {
            toast(getString(R.string.browser_no_page))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, currentTab()?.title.orEmpty())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.browser_share)))
    }

    private fun createSheetPanel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundResource(R.drawable.bg_sheet_panel)
        }

    private fun createSheetTitle(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(0xFF1E2630.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }

    private fun createSheetAction(title: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF1E2630.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.bg_sheet_item)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = sheetItemLayoutParams()
        }

    private fun sheetItemLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56),
        ).apply {
            topMargin = dp(8)
        }

    private fun clearBrowsingData() {
        currentBrowserFragment()?.view?.findViewById<android.webkit.WebView>(R.id.webView)
            ?.clearCache(true)
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        toast(getString(R.string.browser_clear_data))
    }

    private fun updateBottomNavState() {
        val tab = currentTab()
        binding.bottomNav.visibility = View.VISIBLE
        // 网页页（含 WebView 已无历史）：返回按钮保持可点，再点一次回首页；仅首页显示禁用态
        val canGoBack = !isShowingHome
        // 首页：若当前标签页仍有已打开网页，可前进回去；网页内则跟随 WebView 前进历史
        val canGoForward = if (isShowingHome) {
            !tab?.url.isNullOrBlank()
        } else {
            tab?.canGoForward == true
        }
        setNavButtonEnabled(binding.btnBack, canGoBack, R.drawable.ic_page_back, R.drawable.ic_page_back_dis)
        setNavButtonEnabled(
            binding.btnForward,
            canGoForward,
            R.drawable.ic_page_advance,
            R.drawable.ic_page_advance_dis,
        )
        binding.tvTabCount.text = tabs.size.coerceAtLeast(1).toString()
        currentBrowserFragment()?.updateTabCount(tabs.size)
    }

    private fun resumeCurrentTabPage() {
        val tab = currentTab() ?: return
        openTabPage(tab)
    }

    private fun applyContainerBottomInset() {
        binding.browserContainer.updatePadding(
            left = binding.browserContainer.paddingLeft,
            top = binding.browserContainer.paddingTop,
            right = binding.browserContainer.paddingRight,
            bottom = if (isShowingHome) 0 else lastNavBarInset,
        )
    }

    private fun applyBrowserContentInsets() {
        currentTab()?.fragment?.setContentInsets(
            top = lastStatusBarInset,
            bottom = 0, // 底部 inset 已由 browserContainer padding 处理，避免重复留白
        )
    }

    private fun setNavButtonEnabled(
        button: ImageButton,
        enabled: Boolean,
        enabledRes: Int,
        disabledRes: Int,
    ) {
        button.isEnabled = enabled
        button.setImageResource(if (enabled) enabledRes else disabledRes)
        button.alpha = if (enabled) 1f else 0.82f
    }

    private fun currentTab(): BrowserTab? =
        tabs.firstOrNull { it.id == selectedTabId }

    private fun currentBrowserFragment(): BrowserFragment? =
        currentTab()?.fragment?.takeUnless { isShowingHome }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun tabTag(tabId: String): String = "$TAB_TAG_PREFIX$tabId"

    private fun buildSessionSnapshot(): TabSessionSnapshot {
        val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
        return TabSessionSnapshot(
            selectedTabId = selectedTabId,
            selectedTabIndex = selectedIndex,
            showingHome = isShowingHome,
            tabs = tabs.map { tab ->
                PersistedTab(
                    id = tab.id,
                    title = tab.title,
                    url = tab.url,
                    loadState = tab.loadState,
                )
            },
        )
    }

    private fun persistTabsAsync() {
        runCatching { tabSessionRepository.saveAsync(buildSessionSnapshot()) }
            .onFailure { android.util.Log.w(TAG, "persistTabsAsync failed", it) }
    }

    private fun persistTabsBlocking() {
        runCatching { tabSessionRepository.saveBlocking(buildSessionSnapshot()) }
            .onFailure { android.util.Log.w(TAG, "persistTabsBlocking failed", it) }
    }

    private data class BrowserTab(
        val id: String,
        var title: String,
        var url: String? = null,
        var fragment: BrowserFragment? = null,
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        var loadState: TabLoadState = TabLoadState.IDLE,
    )



    companion object {
        const val EXTRA_RESET_BROWSER = "extra_reset_browser"

        fun createBrowserResetIntent(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_RESET_BROWSER, true)
            }
        const val TAG = "MainActivity"
        const val HOME_TAG = "home_fragment"
        const val TAB_TAG_PREFIX = "browser_tab_"
        const val STATE_SELECTED_TAB = "selected_tab"
        const val STATE_TAB_IDS = "tab_ids"
        const val STATE_TAB_TITLES = "tab_titles"
        const val STATE_TAB_URLS = "tab_urls"
        const val STATE_TAB_LOADING = "tab_loading"
        const val STATE_TAB_LOAD_STATES = "tab_load_states"
        const val STATE_SHOWING_HOME = "showing_home"
    }
}
