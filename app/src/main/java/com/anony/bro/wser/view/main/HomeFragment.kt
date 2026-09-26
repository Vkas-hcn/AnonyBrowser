package com.anony.bro.wser.view.main

import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.play.core.review.ReviewManagerFactory
import com.anony.bro.wser.R
import com.anony.bro.wser.ads.AdLoadListener
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.ads.AdShowListener
import com.anony.bro.wser.ads.HomeInterstitialPolicy
import com.anony.bro.wser.ads.BannerAdSlot
import com.anony.bro.wser.hellohello.NoticeConfigStore
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.home.HomeContentItem
import com.anony.bro.wser.data.settings.BrowserSettingsStore
import com.anony.bro.wser.data.settings.BrowserSettingsStore.AddressBarPosition
import com.anony.bro.wser.data.trending.TrendingRepository
import com.anony.bro.wser.databinding.BottomSheetHistoryDeleteBinding
import com.anony.bro.wser.databinding.BottomSheetShortcutEditBinding
import com.anony.bro.wser.databinding.FragmentHomeBinding
import com.anony.bro.wser.databinding.DialogRateUsBinding
import com.anony.bro.wser.databinding.PopupShortcutMenuBinding
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

class HomeFragment : Fragment() {

    interface Callback {
        fun onHomeOpenUrl(url: String, isSearch: Boolean = false)
        fun onRateDialogDismissed()
        fun canLoadHomeNativeAd(): Boolean
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding: FragmentHomeBinding
        get() = _binding ?: error("Home binding is not available")

    private val callback: Callback?
        get() = activity as? Callback
    private val newsViewModel: HomeNewsViewModel by viewModels()

    private val prefs by lazy {
        requireContext().getSharedPreferences("browser_home", Context.MODE_PRIVATE)
    }
    private val ratePrefs by lazy {
        requireContext().getSharedPreferences(PREFS_RATE_US, Context.MODE_PRIVATE)
    }

    private var shortcutMenuPopup: PopupWindow? = null
    private var rateDialog: Dialog? = null
    private var shortcutEditMode = false
    private var shortcutRenderAfterLayout = false
    private var newsAutoScrollJob: Job? = null
    private var newsAutoScrollEnabled = false
    private var bannerLoadListener: AdLoadListener? = null
    private val bannerMode: BannerAdSlot.BannerMode = AdMobManager.bannerModeRegular()
    private var newsIndicatorCount = 0
    private val newsBannerAdapter by lazy {
        HomeContentBannerAdapter(
            onClick = { item -> showHomeInterstitialThen { callback?.onHomeOpenUrl(item.targetUrl) } },
            onBindRemoteImage = ::loadNewsImage,
            onItemsChanged = { count ->
                if (_binding != null) {
                    renderNewsIndicators(count)
                    newsAutoScrollEnabled = count > 1
                }
            },
        )
    }

    private val newsPageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateNewsIndicators(newsBannerAdapter.realPosition(position))
            // 手动/自动切换后都重新计时，避免刚滑完立刻又被自动轮播带走
            if (newsAutoScrollEnabled && _binding != null && !isHidden) {
                startNewsAutoScroll()
            }
        }
    }

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != AppCompatActivity.RESULT_OK) return@registerForActivityResult
        val query = SearchActivity.readQuery(result.data) ?: return@registerForActivityResult
        UpDataTool.trackEvent("click_search")
        openUserInput(query)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSearch()
        setupNewsBanner()
        applyAddressBarPosition(BrowserSettingsStore.getAddressBarPosition(requireContext()))
        setupShortcutsScroll()
        binding.homeContent.post { renderShortcuts() }
        observeNativeAd()
        observeNews()
        newsViewModel.load(requireContext())
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            TrendingRepository.shared.warmUp(appContext)
        }
        if (NoticeConfigStore.homeInterstitialConfig().enabled) {
            AdMobManager.loadInterstitial(requireContext(), AdMobManager.InterstitialPlacement.HOME)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        applyAddressBarPosition(BrowserSettingsStore.getAddressBarPosition(requireContext()))
        renderShortcuts()
        trackHomeViewIfVisible()
        syncBannerAd()
        // 展示刷新由 observeNativeAd 的可见性边沿（wasVisible）统一驱动，避免与 onResume 双触发
    }

    override fun onDestroyView() {
        unbindBannerLoadObserver()
        newsAutoScrollJob?.cancel()
        newsAutoScrollJob = null
        _binding?.newsPager?.unregisterOnPageChangeCallback(newsPageCallback)
        rateDialog?.setOnDismissListener(null)
        rateDialog?.dismiss()
        rateDialog = null
        shortcutMenuPopup?.dismiss()
        shortcutMenuPopup = null
        shortcutRenderAfterLayout = false
        _binding?.nativeAd?.let {
            AdMobManager.releaseNativeAdContainer(it, AdMobManager.NativePlacement.HOME)
        }
        _binding = null
        super.onDestroyView()
    }

    fun onBrowserDataReset() {
        if (_binding == null) return
        applyAddressBarPosition(BrowserSettingsStore.getAddressBarPosition(requireContext()))
        renderShortcuts()
    }

    fun isRateDialogShowing(): Boolean = rateDialog?.isShowing == true

    fun showRateDialogIfNeeded() {
        val host = activity ?: return
        if (!isAdded || rateDialog?.isShowing == true || ratePrefs.getBoolean(KEY_RATE_COMPLETED, false)) {
            return
        }
        val dialog = Dialog(host)
        val dialogBinding = DialogRateUsBinding.inflate(host.layoutInflater)
        var selectedStars = 5
        val stars = listOf(
            dialogBinding.btnStar1,
            dialogBinding.btnStar2,
            dialogBinding.btnStar3,
            dialogBinding.btnStar4,
            dialogBinding.btnStar5,
        )

        fun renderSelection() {
            stars.forEachIndexed { index, star ->
                star.setImageResource(
                    if (index < selectedStars) R.drawable.icon_star_selected
                    else R.drawable.icon_star_unselected,
                )
            }
            val isLowRating = selectedStars in 1..3
            dialogBinding.tvRateMessage.setText(
                if (isLowRating) R.string.rate_us_low_message else R.string.rate_us_high_message,
            )
            dialogBinding.btnRateAction.apply {
                isEnabled = selectedStars > 0
                setText(if (isLowRating) R.string.rate_us_submit else R.string.rate_us_sure)
            }
        }

        stars.forEachIndexed { index, star ->
            star.setOnClickListener {
                selectedStars = index + 1
                renderSelection()
            }
        }
        renderSelection()
        dialogBinding.btnRateAction.setOnClickListener {
            ratePrefs.edit { putBoolean(KEY_RATE_COMPLETED, true) }
            dialog.dismiss()
            if (selectedStars < 4) {
                showToast(getString(R.string.rate_us_success_message))
            } else {
                Log.i(TAG, "Rate dialog submitted with $selectedStars stars; requesting Google Play in-app review")
                openGooglePlayReview()
            }
        }
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (rateDialog === dialog) {
                rateDialog = null
                callback?.onRateDialogDismissed()
            }
        }
        rateDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun openGooglePlayReview() {
        val host = activity
        if (host == null) {
            Log.w(TAG, "Cannot request in-app review: HomeFragment is detached")
            return
        }
        try {
            val manager = ReviewManagerFactory.create(host)
            Log.d(TAG, "Requesting Google Play in-app review flow for ${host.packageName}")
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful) {
                    Log.w(TAG, "In-app review flow request failed; falling back to store", request.exception)
                    openGooglePlayStore(host)
                    return@addOnCompleteListener
                }
                try {
                    Log.d(TAG, "In-app review flow requested successfully; launching Google Play review UI")
                    manager.launchReviewFlow(host, request.result).addOnCompleteListener { launch ->
                        if (launch.isSuccessful) {
                            Log.i(
                                TAG,
                                "Google Play review flow completed. Play does not guarantee that a review card was shown or submitted.",
                            )
                        } else {
                            Log.w(TAG, "In-app review UI launch failed; falling back to store", launch.exception)
                            openGooglePlayStore(host)
                        }
                    }
                } catch (exception: Exception) {
                    Log.w(TAG, "In-app review UI launch threw; falling back to store", exception)
                    openGooglePlayStore(host)
                }
            }
        } catch (exception: Exception) {
            Log.w(TAG, "In-app review setup threw; falling back to store", exception)
            openGooglePlayStore(host)
        }
    }

    private fun openGooglePlayStore(context: Context) {
        val packageName = context.packageName
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            Log.i(TAG, "Opened Play Store market page for $packageName")
        } catch (marketException: Exception) {
            Log.w(TAG, "Play Store market intent failed; trying browser fallback", marketException)
            try {
                context.startActivity(webIntent)
                Log.i(TAG, "Opened Google Play web page for $packageName")
            } catch (webException: Exception) {
                Log.e(TAG, "Google Play web fallback failed", webException)
                Toast.makeText(context, R.string.settings_play_store_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setContentInsets(top: Int, bottom: Int) {
        if (_binding == null) return
        binding.homeContent.setPadding(
            binding.homeContent.paddingLeft,
            dp(12) + top,
            binding.homeContent.paddingRight,
            dp(24) + bottom,
        )
        binding.root.post {
            if (_binding == null) return@post
            ensureContentFillsViewport()
            applyAddressBarPosition(BrowserSettingsStore.getAddressBarPosition(requireContext()))
        }
    }

    private fun ensureContentFillsViewport() {
        val viewportHeight = binding.root.height
        if (viewportHeight <= 0) return
        val minHeight = (viewportHeight - binding.homeContent.paddingTop - binding.homeContent.paddingBottom)
            .coerceAtLeast(0)
        if (binding.homeContent.minimumHeight != minHeight) {
            binding.homeContent.minimumHeight = minHeight
        }
    }

    private fun applyAddressBarPosition(position: AddressBarPosition) {
        if (_binding == null) return
        ensureContentFillsViewport()
        val set = ConstraintSet()
        set.clone(binding.homeContent)

        listOf(
            R.id.newsBanner,
            R.id.searchBox,
            R.id.shortcutsScroll,
            R.id.nativeAd,
        ).forEach { id ->
            set.clear(id, ConstraintSet.TOP)
            set.clear(id, ConstraintSet.BOTTOM)
        }
        set.setVerticalBias(R.id.newsBanner, 0.5f)
        set.setVerticalBias(R.id.searchBox, 0.5f)

        when (position) {
            // Bottom and center address bars place the ad below the news banner.
            AddressBarPosition.CENTER -> {
                set.connect(R.id.newsBanner, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.nativeAd, ConstraintSet.TOP, R.id.newsBanner, ConstraintSet.BOTTOM)
                set.setMargin(R.id.nativeAd, ConstraintSet.TOP, dp(12))
                set.connect(R.id.searchBox, ConstraintSet.TOP, R.id.nativeAd, ConstraintSet.BOTTOM)
                set.setMargin(R.id.searchBox, ConstraintSet.TOP, dp(56))
                set.connect(R.id.shortcutsScroll, ConstraintSet.TOP, R.id.searchBox, ConstraintSet.BOTTOM)
                set.setMargin(R.id.shortcutsScroll, ConstraintSet.TOP, dp(36))
            }
            AddressBarPosition.BOTTOM -> {
                set.connect(R.id.newsBanner, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.nativeAd, ConstraintSet.TOP, R.id.newsBanner, ConstraintSet.BOTTOM)
                set.setMargin(R.id.nativeAd, ConstraintSet.TOP, dp(12))
                set.connect(R.id.shortcutsScroll, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.connect(R.id.searchBox, ConstraintSet.BOTTOM, R.id.shortcutsScroll, ConstraintSet.TOP)
                set.setMargin(R.id.searchBox, ConstraintSet.BOTTOM, dp(16))
                // searchBox 顶部锚定到广告下方，形成完整链避免与顶部广告重叠
                set.connect(R.id.searchBox, ConstraintSet.TOP, R.id.nativeAd, ConstraintSet.BOTTOM)
                set.setMargin(R.id.searchBox, ConstraintSet.TOP, dp(16))
                set.setVerticalBias(R.id.searchBox, 1f)
            }
            // The top address bar places the news banner below the shortcuts and ad.
            AddressBarPosition.TOP -> {
                set.connect(R.id.searchBox, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.shortcutsScroll, ConstraintSet.TOP, R.id.searchBox, ConstraintSet.BOTTOM)
                set.setMargin(R.id.shortcutsScroll, ConstraintSet.TOP, dp(36))
                set.connect(R.id.nativeAd, ConstraintSet.TOP, R.id.shortcutsScroll, ConstraintSet.BOTTOM)
                set.setMargin(R.id.nativeAd, ConstraintSet.TOP, dp(12))
                set.connect(R.id.newsBanner, ConstraintSet.TOP, R.id.nativeAd, ConstraintSet.BOTTOM)
                set.setMargin(R.id.newsBanner, ConstraintSet.TOP, dp(12))
                set.connect(R.id.newsBanner, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.setVerticalBias(R.id.newsBanner, 1f)
            }
        }

        set.applyTo(binding.homeContent)
    }

    private fun setupShortcutsScroll() {
        binding.shortcutsScroll.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                -> view.parent?.requestDisallowInterceptTouchEvent(
                    view.canScrollVertically(-1) || view.canScrollVertically(1),
                )
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun setupSearch() {
        val openSearchPage = View.OnClickListener {
            searchLauncher.launch(SearchActivity.createIntent(requireContext()))
        }
        binding.searchBox.setOnClickListener(openSearchPage)
        binding.searchInput.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            isCursorVisible = false
            setOnClickListener(openSearchPage)
        }
    }

    private fun setupNewsBanner() {
        binding.newsBanner.clipToOutline = true
        binding.newsPager.apply {
            adapter = newsBannerAdapter
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            (getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            registerOnPageChangeCallback(newsPageCallback)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            newsAutoScrollJob?.cancel()
            newsAutoScrollJob = null
        } else if (newsAutoScrollEnabled && _binding != null) {
            startNewsAutoScroll()
        }
        if (!hidden) trackHomeViewIfVisible()
        syncBannerAd()
    }

    private fun trackHomeViewIfVisible() {
        if (_binding == null || !isResumed || isHidden) return
        UpDataTool.trackEvent("home_view")
    }

    /** 首页可见时：已展示则跳过；有缓存则挂上；否则加载。 */
    private fun syncBannerAd() {
        val visible = _binding != null && isResumed && !isHidden
        if (!visible) {
            unbindBannerLoadObserver()
            return
        }
        val host = (activity as? MainActivity)?.bannerOverlay() ?: return
        bindBannerLoadObserver(host)
        if (AdMobManager.isBannerDisplayedInMode(host, bannerMode)) return
        if (AdMobManager.hasValidBannerCache(bannerMode)) {
            AdMobManager.updateBannerContainer(host, bannerMode)
        } else if (!AdMobManager.isBannerLoading(bannerMode)) {
            AdMobManager.loadBanner(requireContext(), bannerMode)
        }
    }

    private fun bindBannerLoadObserver(host: ViewGroup) {
        if (bannerLoadListener != null) return
        val listener = object : AdLoadListener {
            override fun onAdLoaded() {
                if (_binding == null || !isResumed || isHidden) return
                if (!host.isAttachedToWindow) return
                AdMobManager.updateBannerContainer(host, bannerMode)
            }

            override fun onAdFailedToLoad(error: String) = Unit
        }
        bannerLoadListener = listener
        AdMobManager.addBannerLoadObserver(bannerMode, listener)
    }

    private fun unbindBannerLoadObserver() {
        val listener = bannerLoadListener ?: return
        AdMobManager.removeBannerLoadObserver(bannerMode, listener)
        bannerLoadListener = null
    }

    private fun observeNews() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                newsViewModel.state.collect(::renderNews)
            }
        }
    }

    private fun observeNativeAd() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var connectNativeLoadTriggered = false
                var wasVisible = false
                while (true) {
                    if (_binding == null) return@repeatOnLifecycle
                    // show/hide 场景用 isHidden；避免 isVisible 依赖 windowToken 抖动导致反复 forceRefresh
                    val visible = isResumed && !isHidden && callback?.canLoadHomeNativeAd() != false
                    if (visible) {
                        if (!connectNativeLoadTriggered) {
                            connectNativeLoadTriggered = true
                            AdMobManager.preloadNativeAd(
                                requireContext().applicationContext,
                                AdMobManager.NativePlacement.CONNECT,
                            )
                        }
                        val state = AdMobManager.updateNativeAdContainer(
                            binding.nativeAd,
                            AdMobManager.NativePlacement.HOME,
                            forceRefresh = !wasVisible,
                        )
                        wasVisible = true
                        // 仅在真正加载中快轮询；已展示/失败等待时放慢，避免打爆请求
                        val loading = AdMobManager.isNativeAdLoading(AdMobManager.NativePlacement.HOME)
                        delay(
                            if (state == AdMobManager.NativeContainerState.LOADING && loading) {
                                NATIVE_AD_POLL_INTERVAL_MS
                            } else {
                                NATIVE_AD_IDLE_POLL_INTERVAL_MS
                            },
                        )
                        continue
                    } else {
                        connectNativeLoadTriggered = false
                        wasVisible = false
                    }
                    delay(NATIVE_AD_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun renderNews(state: HomeNewsState) {
        if (_binding == null) return
        when (state) {
            HomeNewsState.Loading -> {
                binding.newsPager.visibility = View.VISIBLE
                binding.newsEmptyState.visibility = View.GONE
                submitNewsBanner(List(1) { null }, autoScroll = false)
            }
            is HomeNewsState.Content -> {
                // Missing images deliberately keep their placeholder until background resolution finishes.
                val bannerArticles = state.items
                binding.newsPager.visibility = View.VISIBLE
                binding.newsEmptyState.visibility = View.GONE
                submitNewsBanner(bannerArticles, autoScroll = bannerArticles.size > 1)
            }
            HomeNewsState.Empty -> {
                stopNewsAutoScroll()
                newsBannerAdapter.submit(emptyList())
                renderNewsIndicators(0)
                binding.newsPager.visibility = View.GONE
                binding.newsEmptyState.visibility = View.VISIBLE
            }
        }
    }

    private fun submitNewsBanner(articles: List<HomeContentItem?>, autoScroll: Boolean) {
        newsBannerAdapter.submit(articles)
        binding.newsPager.setCurrentItem(newsBannerAdapter.startPosition(), false)
        val count = newsBannerAdapter.realCount()
        renderNewsIndicators(count)
        newsAutoScrollEnabled = autoScroll && count > 1
        if (autoScroll) {
            startNewsAutoScroll()
        } else {
            stopNewsAutoScroll()
        }
    }

    private fun renderNewsIndicators(count: Int) {
        newsIndicatorCount = count
        val container = binding.newsIndicators
        container.removeAllViews()
        if (count <= 1) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val size = dp(8)
        val gap = dp(6)
        repeat(count) { index ->
            container.addView(
                View(requireContext()).apply {
                    background = requireContext().getDrawable(
                        if (index == 0) {
                            R.drawable.bg_news_indicator_selected
                        } else {
                            R.drawable.bg_news_indicator_unselected
                        },
                    )
                },
                LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = gap
                },
            )
        }
    }

    private fun updateNewsIndicators(selected: Int) {
        val container = binding.newsIndicators
        if (newsIndicatorCount <= 1 || container.childCount == 0) return
        for (i in 0 until container.childCount) {
            container.getChildAt(i).background = requireContext().getDrawable(
                if (i == selected) {
                    R.drawable.bg_news_indicator_selected
                } else {
                    R.drawable.bg_news_indicator_unselected
                },
            )
        }
    }

    private fun startNewsAutoScroll() {
        newsAutoScrollJob?.cancel()
        newsAutoScrollJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(NEWS_AUTO_SCROLL_MS)
                    if (_binding == null || !newsAutoScrollEnabled || isHidden) continue
                    val pager = binding.newsPager
                    val count = newsBannerAdapter.realCount()
                    if (count <= 1 || pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) continue
                    pager.setCurrentItem(pager.currentItem + 1, true)
                }
            }
        }
    }

    private fun stopNewsAutoScroll() {
        newsAutoScrollEnabled = false
        newsAutoScrollJob?.cancel()
        newsAutoScrollJob = null
    }

    private fun loadNewsImage(url: String, image: ImageView, onFailed: () -> Unit) {
        image.tag = url
        val cacheFile = File(requireContext().cacheDir, "news_images/${url.hashCode()}.png")
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(cacheFile.absolutePath) ?: downloadBitmap(url)?.also { bitmap ->
                    cacheFile.parentFile?.mkdirs()
                    FileOutputStream(cacheFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }
            }
            if (_binding != null && image.tag == url && bitmap != null) image.setImageBitmap(bitmap)
            else if (_binding != null && image.tag == url) onFailed()
        }
    }

    private fun openUserInput(rawInput: String) {
        val target = when (val result = buildNavigationTarget(rawInput)) {
            InputTarget.Blank -> return
            InputTarget.Invalid -> {
                showToast(getString(R.string.browser_invalid_url))
                return
            }
            is InputTarget.Url -> NavigationTarget(result.url, isSearch = false)
            is InputTarget.Search -> NavigationTarget(result.url, isSearch = true)
        }
        if (!isNetworkAvailable()) {
            showToast(getString(R.string.browser_no_internet_connection))
            return
        }
        hideKeyboard(binding.searchInput)
        binding.searchInput.setText("")
        callback?.onHomeOpenUrl(target.url, target.isSearch)
    }

    private fun renderShortcuts() {
        val grid = binding.shortcutsGrid
        val contentWidth = shortcutGridContentWidth(grid)
        if (contentWidth <= 0) {
            scheduleShortcutsRenderAfterLayout()
            return
        }
        grid.removeAllViews()
        val shortcuts = loadShortcuts()
        if (shortcuts.isEmpty()) {
            grid.columnCount = 1
            grid.addView(createEmptyShortcutsView(), GridLayout.LayoutParams().apply {
                width = contentWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            })
            updateShortcutsScrollHeight(itemCount = 0)
            return
        }
        grid.columnCount = SHORTCUT_COLUMN_COUNT
        val items = shortcuts + Shortcut.addButton()
        val cellWidth = contentWidth / SHORTCUT_COLUMN_COUNT
        items.forEachIndexed { index, shortcut ->
            grid.addView(createShortcutView(shortcut, index), GridLayout.LayoutParams().apply {
                width = cellWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(0, 0, 0, dp(20))
            })
        }
        updateShortcutsScrollHeight(items.size)
    }

    private fun updateShortcutsScrollHeight(itemCount: Int) {
        val rows = ((itemCount + SHORTCUT_COLUMN_COUNT - 1) / SHORTCUT_COLUMN_COUNT)
            .coerceAtLeast(1)
        val targetHeight = if (rows > SHORTCUT_VISIBLE_ROW_COUNT) {
            dp(SHORTCUT_MAX_HEIGHT_DP)
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val params = binding.shortcutsScroll.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            binding.shortcutsScroll.layoutParams = params
        }
    }

    private fun shortcutGridContentWidth(grid: GridLayout): Int {
        val gridWidth = grid.width - grid.paddingLeft - grid.paddingRight
        if (gridWidth > 0) return gridWidth
        val parent = binding.homeContent
        val parentHorizontalPadding = parent.paddingLeft + parent.paddingRight
        val parentWidth = parent.width - parentHorizontalPadding
        if (parentWidth > 0) return parentWidth
        val rootWidth = binding.root.width - parentHorizontalPadding
        if (rootWidth > 0) return rootWidth
        return resources.displayMetrics.widthPixels - parentHorizontalPadding
    }

    private fun scheduleShortcutsRenderAfterLayout() {
        if (shortcutRenderAfterLayout || _binding == null) return
        shortcutRenderAfterLayout = true
        binding.homeContent.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                view.removeOnLayoutChangeListener(this)
                shortcutRenderAfterLayout = false
                if (_binding != null) {
                    renderShortcuts()
                }
            }
        })
        binding.homeContent.post {
            if (_binding == null || !shortcutRenderAfterLayout) return@post
            shortcutRenderAfterLayout = false
            renderShortcuts()
        }
    }

    private fun createShortcutView(shortcut: Shortcut, index: Int): View {
        val iconSize = dp(56)
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragStarted = false
        val item = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            foreground = selectableBorderless()
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                if (shortcut.isAdd) {
                    showHomeInterstitialThen { showAddShortcutDialog() }
                } else {
                    shortcut.trackClick()
                    shortcut.url?.let { url -> showHomeInterstitialThen { callback?.onHomeOpenUrl(url) } }
                }
            }
            if (!shortcut.isAdd) {
                setOnLongClickListener {
                    shortcutEditMode = true
                    showShortcutMenu(this, shortcut)
                    true
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            dragStarted = false
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (shortcutEditMode && !dragStarted && dx * dx + dy * dy > touchSlop * touchSlop) {
                                dragStarted = true
                                shortcutMenuPopup?.dismiss()
                                view.startDragAndDrop(
                                    ClipData.newPlainText("shortcut_id", shortcut.id),
                                    View.DragShadowBuilder(view),
                                    shortcut.id,
                                    0,
                                )
                                return@setOnTouchListener true
                            }
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL,
                        -> dragStarted = false
                    }
                    false
                }
                setOnDragListener { _, event ->
                    handleShortcutDrag(event, shortcut.id)
                }
            }
        }

        item.addView(createShortcutIcon(shortcut), LinearLayout.LayoutParams(iconSize, iconSize))

        item.addView(TextView(requireContext()).apply {
            text = shortcut.name
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            includeFontPadding = false
            maxLines = 1
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
        })
        return item
    }

    private fun showHomeInterstitialThen(action: () -> Unit) {
        val config = NoticeConfigStore.homeInterstitialConfig()
        if (!HomeInterstitialPolicy.canAttempt(config.enabled)) {
            action()
            return
        }
        var completed = false
        var didShow = false
        fun complete() {
            if (!didShow) HomeInterstitialPolicy.releaseAttempt()
            if (!completed) {
                completed = true
                action()
            }
        }
        val shown = AdMobManager.showInterstitial(requireActivity(), AdMobManager.InterstitialPlacement.HOME,
            object : AdShowListener {
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

    private fun createShortcutIcon(shortcut: Shortcut): View {
        val faviconPath = shortcut.faviconPath?.takeIf { File(it).exists() }
        return MaterialCardView(requireContext()).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            clipToOutline = true
            preventCornerOverlap = false
            setCardBackgroundColor(Color.TRANSPARENT)
            addView(
                if (shortcut.iconResName != null || faviconPath != null || shortcut.isAdd) {
                    ImageView(requireContext()).apply {
                        contentDescription = shortcut.name
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        when {
                            shortcut.isAdd -> setImageResource(R.drawable.ic_add_page)
                            faviconPath != null -> setImageBitmap(BitmapFactory.decodeFile(faviconPath))
                            else -> setImageResource(shortcut.iconResId())
                        }
                    }
                } else {
                    TextView(requireContext()).apply {
                        text = shortcut.name.take(1).uppercase()
                        textSize = 22f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setTextColor(0xFF0B80F7.toInt())
                        setBackgroundResource(R.drawable.bg_shortcut_custom)
                    }
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            if (!shortcut.isAdd && shortcut.iconResName == null && faviconPath == null) {
                loadShortcutFavicon(shortcut, this)
            }
        }
    }

    private fun createEmptyShortcutsView(): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
            addView(createShortcutIcon(Shortcut.addButton()), LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(TextView(requireContext()).apply {
                text = getString(R.string.shortcut_empty)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            })
            isClickable = true
            setOnClickListener { showHomeInterstitialThen { showAddShortcutDialog() } }
        }
    }

    private fun showAddShortcutDialog(shortcut: Shortcut? = null): Dialog {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetShortcutEditBinding.inflate(layoutInflater)
        sheetBinding.tvSheetTitle.setText(
            if (shortcut == null) R.string.shortcut_add_title else R.string.shortcut_edit_title,
        )
        sheetBinding.inputName.setText(shortcut?.name.orEmpty())
        sheetBinding.inputUrl.setText(shortcut?.url.orEmpty())
        sheetBinding.btnSave.setOnClickListener {
            val name = sheetBinding.inputName.text?.toString()?.trim().orEmpty()
            val url = buildTargetUrl(sheetBinding.inputUrl.text?.toString().orEmpty())
            if (name.isBlank() || url == null) {
                sheetBinding.inputUrl.error = getString(R.string.browser_invalid_shortcut)
                return@setOnClickListener
            }
            val shortcuts = loadShortcuts().toMutableList()
            val shouldClearOldFavicon = shortcut?.faviconPath != null && shortcut.url != url
            val updated = Shortcut(
                id = shortcut?.id ?: UUID.randomUUID().toString(),
                name = name,
                url = url,
                iconResName = shortcut?.iconResName,
                faviconPath = shortcut?.faviconPath?.takeIf { shortcut.url == url },
            )
            val index = shortcuts.indexOfFirst { it.id == updated.id }
            if (index >= 0) {
                shortcuts[index] = updated
            } else {
                shortcuts += updated
            }
            saveShortcuts(shortcuts)
            if (shouldClearOldFavicon) {
                lifecycleScope.launch(Dispatchers.IO) {
                    deleteShortcutFavicon(shortcut)
                }
            }
            dialog.dismiss()
            renderShortcuts()
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheetBinding.root)
        dialog.setCancelable(true)
        dialog.behavior.isDraggable = true
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        dialog.show()
        return dialog
    }

    private fun showShortcutMenu(anchor: View, shortcut: Shortcut) {
        shortcutMenuPopup?.dismiss()
        val menuBinding = PopupShortcutMenuBinding.inflate(layoutInflater)
        val popupWidth = dp(160)
        menuBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = menuBinding.root.measuredHeight
        val popup = PopupWindow(menuBinding.root, popupWidth, popupHeight, true).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        menuBinding.menuEdit.setOnClickListener {
            popup.dismiss()
            showAddShortcutDialog(shortcut)
        }
        menuBinding.menuDelete.setOnClickListener {
            popup.dismiss()
            showDeleteShortcutConfirm(shortcut)
        }
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val x = (location[0] + anchor.width / 2 - popupWidth / 2)
            .coerceIn(dp(8), resources.displayMetrics.widthPixels - popupWidth - dp(8))
        val y = location[1] + anchor.height + dp(4)
        shortcutMenuPopup = popup
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
    }

    private fun showDeleteShortcutConfirm(shortcut: Shortcut) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetHistoryDeleteBinding.inflate(layoutInflater)
        sheetBinding.tvDeleteMessage.setText(R.string.shortcut_delete_confirm)
        dialog.setContentView(sheetBinding.root)
        dialog.setCancelable(true)
        dialog.behavior.isDraggable = true
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        sheetBinding.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            val shortcuts = loadShortcuts().filterNot { it.id == shortcut.id }
            saveShortcuts(shortcuts)
            lifecycleScope.launch(Dispatchers.IO) {
                deleteShortcutFavicon(shortcut)
            }
            renderShortcuts()
        }
        sheetBinding.btnCancelDelete.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleShortcutDrag(event: DragEvent, targetId: String): Boolean {
        if (!shortcutEditMode) return false
        if (event.action != DragEvent.ACTION_DROP) return true
        val draggedId = event.localState as? String ?: return true
        if (draggedId == targetId) return true
        val shortcuts = loadShortcuts().toMutableList()
        val from = shortcuts.indexOfFirst { it.id == draggedId }
        val to = shortcuts.indexOfFirst { it.id == targetId }
        if (from >= 0 && to >= 0) {
            val moved = shortcuts.removeAt(from)
            shortcuts.add(to.coerceAtMost(shortcuts.size), moved)
            saveShortcuts(shortcuts)
            renderShortcuts()
        }
        return true
    }

    private fun loadShortcuts(): List<Shortcut> {
        if (!prefs.contains(KEY_SHORTCUTS)) {
            val migrated = defaultShortcuts() + loadLegacyCustomShortcuts()
            saveShortcuts(migrated)
            return migrated
        }
        val raw = prefs.getString(KEY_SHORTCUTS, "[]") ?: "[]"
        return parseShortcuts(raw)
    }

    private fun parseShortcuts(raw: String): List<Shortcut> =
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (name.isBlank() || url.isBlank()) continue
                    add(
                        Shortcut(
                            id = item.optString("id").takeIf { it.isNotBlank() }
                                ?: UUID.randomUUID().toString(),
                            name = name,
                            url = url,
                            iconResName = item.optString("iconResName").takeIf { it.isNotBlank() },
                            faviconPath = item.optString("faviconPath").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

    private fun loadLegacyCustomShortcuts(): List<Shortcut> {
        val raw = prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (name.isNotBlank() && url.isNotBlank()) {
                        add(Shortcut(UUID.randomUUID().toString(), name, url, null, null))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveShortcuts(shortcuts: List<Shortcut>) {
        val array = JSONArray()
        shortcuts.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("url", it.url)
                put("iconResName", it.iconResName.orEmpty())
                put("faviconPath", it.faviconPath.orEmpty())
            })
        }
        prefs.edit { putString(KEY_SHORTCUTS, array.toString()) }
    }

    private fun defaultShortcuts(): List<Shortcut> {
        return listOf(
            Shortcut("default_youtube", "YouTube", "https://www.youtube.com", "ic_youtube", null),
            Shortcut("default_facebook", "Facebook", "https://www.facebook.com", "ic_facebook", null),
            Shortcut("default_amazon", "Amazon", "https://www.amazon.com", "ic_amazon", null),
            Shortcut("default_tiktok", "Tiktok", "https://www.tiktok.com", "ic_tiktok", null),
            Shortcut("default_wikipedia", "Wikipedia", "https://www.wikipedia.org", "ic_wikipedia", null),
            Shortcut("default_x", "X", "https://x.com", "ic_x", null),
        )
    }

    private fun loadShortcutFavicon(shortcut: Shortcut, iconContainer: MaterialCardView) {
        val url = shortcut.url ?: return
        lifecycleScope.launch {
            val faviconPath = withContext(Dispatchers.IO) {
                fetchAndCacheFavicon(shortcut.id, url)
            } ?: return@launch
            saveShortcutFavicon(shortcut.id, faviconPath)
            val bitmap = BitmapFactory.decodeFile(faviconPath) ?: return@launch
            if (_binding != null) {
                iconContainer.removeAllViews()
                iconContainer.addView(ImageView(requireContext()).apply {
                    contentDescription = shortcut.name
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bitmap)
                }, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        }
    }

    private fun fetchAndCacheFavicon(shortcutId: String, rawUrl: String): String? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: "https"
        val candidates = listOf(
            "$scheme://$host/favicon.ico",
            "https://www.google.com/s2/favicons?domain=$host&sz=128",
        )
        val bitmap = candidates.firstNotNullOfOrNull { candidate ->
            runCatching { downloadBitmap(candidate) }.getOrNull()
        } ?: return null
        val file = File(requireContext().filesDir, "shortcut_favicons/$shortcutId.png")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun downloadBitmap(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                null
            } else {
                connection.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun saveShortcutFavicon(shortcutId: String, faviconPath: String) {
        val shortcuts = loadShortcuts()
        if (shortcuts.none { it.id == shortcutId }) {
            File(faviconPath).delete()
            return
        }
        saveShortcuts(shortcuts.map {
            if (it.id == shortcutId) it.copy(faviconPath = faviconPath) else it
        })
    }

    private fun deleteShortcutFavicon(shortcut: Shortcut?) {
        val path = shortcut?.faviconPath ?: return
        runCatching { File(path).delete() }
    }

    private fun Shortcut.trackClick() {
        val event = when (id) {
            "default_youtube" -> "favorites_click_yt"
            "default_facebook" -> "favorites_click_fb"
            "default_amazon" -> "favorites_click_amz"
            "default_tiktok" -> "favorites_click_tt"
            "default_wikipedia" -> "favorites_click_wk"
            "default_x" -> "favorites_click_x"
            else -> eventForShortcutUrl(url)
        } ?: return
        UpDataTool.trackEvent(event)
    }

    private fun eventForShortcutUrl(url: String?): String? {
        val host = runCatching { URI(url.orEmpty()).host?.removePrefix("www.")?.lowercase() }
            .getOrNull()
        return when (host) {
            "youtube.com" -> "favorites_click_yt"
            "facebook.com" -> "favorites_click_fb"
            "amazon.com" -> "favorites_click_amz"
            "tiktok.com" -> "favorites_click_tt"
            "wikipedia.org" -> "favorites_click_wk"
            "x.com" -> "favorites_click_x"
            else -> null
        }
    }

    private fun buildTargetUrl(input: String): String? {
        return when (val target = buildNavigationTarget(input)) {
            is InputTarget.Url -> target.url
            is InputTarget.Search -> target.url
            InputTarget.Blank,
            InputTarget.Invalid,
            -> null
        }
    }

    private fun buildNavigationTarget(input: String): InputTarget {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return InputTarget.Blank
        val lower = trimmed.lowercase()
        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
        if (hasScheme) {
            return normalizeHttpUrl(trimmed)?.let(InputTarget::Url) ?: InputTarget.Invalid
        }
        if (trimmed.contains("://")) {
            return InputTarget.Invalid
        }
        val looksLikeUrl = trimmed.contains(".") &&
            !trimmed.contains(" ") &&
            !trimmed.contains("\n")
        return if (looksLikeUrl) {
            normalizeHttpUrl("https://$trimmed")?.let(InputTarget::Url) ?: InputTarget.Invalid
        } else {
            val query = URLEncoder.encode(trimmed, Charsets.UTF_8.name())
            InputTarget.Search("https://www.google.com/search?q=$query")
        }
    }

    private fun normalizeHttpUrl(rawUrl: String): String? =
        runCatching {
            val uri = URI(rawUrl)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme !in setOf("http", "https") || !isValidHost(host)) {
                return@runCatching null
            }
            uri.toASCIIString()
        }.getOrNull()

    private fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) return false
        return true
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = requireContext().getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showToast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun selectableBorderless() =
        android.util.TypedValue().let { typedValue ->
            requireContext().theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                typedValue,
                true,
            )
            requireContext().getDrawable(typedValue.resourceId)
        }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class Shortcut(
        val id: String,
        val name: String,
        val url: String?,
        val iconResName: String?,
        val faviconPath: String?,
        val isAdd: Boolean = false,
    ) {
        fun iconResId(): Int = when (iconResName) {
            "ic_youtube" -> R.drawable.ic_youtube
            "ic_facebook" -> R.drawable.ic_facebook
            "ic_amazon" -> R.drawable.ic_amazon
            "ic_tiktok" -> R.drawable.ic_tiktok
            "ic_wikipedia" -> R.drawable.ic_wikipedia
            "ic_x" -> R.drawable.ic_x
            "ic_add_page" -> R.drawable.ic_add_page
            else -> R.mipmap.ic_launcher_round
        }

        companion object {
            fun addButton(): Shortcut =
                Shortcut("shortcut_add", "Add", null, "ic_add_page", null, isAdd = true)
        }
    }

    private sealed class InputTarget {
        data object Blank : InputTarget()
        data object Invalid : InputTarget()
        data class Url(val url: String) : InputTarget()
        data class Search(val url: String) : InputTarget()
    }

    private data class NavigationTarget(
        val url: String,
        val isSearch: Boolean,
    )

    private companion object {
        const val TAG = "HomeFragment"
        const val PREFS_RATE_US = "rate_us"
        const val KEY_RATE_COMPLETED = "completed"
        const val SHORTCUT_COLUMN_COUNT = 4
        const val SHORTCUT_VISIBLE_ROW_COUNT = 2
        const val SHORTCUT_MAX_HEIGHT_DP = 220
        const val NATIVE_AD_POLL_INTERVAL_MS = 300L
        const val NATIVE_AD_IDLE_POLL_INTERVAL_MS = 2_000L
        const val KEY_SHORTCUTS = "shortcuts"
        const val KEY_CUSTOM_SHORTCUTS = "custom_shortcuts"
        const val NEWS_AUTO_SCROLL_MS = 4_000L
    }
}
