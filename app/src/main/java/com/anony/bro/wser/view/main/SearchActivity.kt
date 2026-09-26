package com.anony.bro.wser.view.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.anony.bro.wser.R
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.ads.AdShowListener
import com.anony.bro.wser.data.search.SearchHistoryStore
import com.anony.bro.wser.data.trending.TrendingRepository
import com.anony.bro.wser.data.settings.AppLanguageStore
import com.anony.bro.wser.data.settings.BrowserSettingsStore
import com.anony.bro.wser.databinding.ActivitySearchBinding
import com.anony.bro.wser.databinding.BottomSheetHistoryDeleteBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val repository = TrendingRepository.shared
    private val historyStore by lazy { SearchHistoryStore(this) }

    private var keywords: List<String> = emptyList()
    private var historyItems: List<String> = emptyList()
    private var historyMode = HistoryMode.COLLAPSED
    private var pageIndex = 0
    private var loadJob: Job? = null
    private var lastRefreshAtMs = 0L
    private var refreshingNetwork = false
    private var deleteAllSheet: BottomSheetDialog? = null
    private var finishingForBack = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageStore.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (!isChangingConfigurations && AppLanguageStore.needsRecreate(this)) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        setupActions()
        observeNativeAd()
        AdMobManager.loadInterstitial(applicationContext, AdMobManager.InterstitialPlacement.SEARCH_BACK)
        pageIndex = savedInstanceState?.getInt(STATE_PAGE, 0) ?: 0
        historyMode = savedInstanceState?.getString(STATE_HISTORY_MODE)
            ?.let { runCatching { HistoryMode.valueOf(it) }.getOrNull() }
            ?: HistoryMode.COLLAPSED
        historyItems = historyStore.load()
        if (historyItems.isEmpty()) historyMode = HistoryMode.COLLAPSED
        renderHistory()
        loadTrending(forceNetwork = false, showLoading = false)
        binding.searchInput.post {
            binding.searchInput.requestFocus()
            showKeyboard(binding.searchInput)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, pageIndex)
        outState.putString(STATE_HISTORY_MODE, historyMode.name)
    }

    override fun onDestroy() {
        deleteAllSheet?.dismiss()
        AdMobManager.releaseNativeAdContainer(
            binding.nativeAd,
            AdMobManager.NativePlacement.HOME,
        )
        super.onDestroy()
    }

    private fun setupSystemBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#FFF6F8FC")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.root.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finishWithBackInterstitial() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithBackInterstitial()
            }
        })
        binding.btnSearch.setOnClickListener { submitSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.btnRefresh.setOnClickListener { onRefreshClicked() }
        binding.historyExpandedBar.setOnClickListener { setHistoryMode(HistoryMode.MANAGING) }
        binding.btnHistoryDeleteAll.setOnClickListener { confirmDeleteAllHistory() }
        binding.btnHistoryFinish.setOnClickListener { setHistoryMode(HistoryMode.EXPANDED) }
    }

    private fun finishWithBackInterstitial() {
        if (finishingForBack) return
        finishingForBack = true
        hideKeyboard(binding.searchInput)
        fun complete() {
            if (!isFinishing && !isDestroyed) finish()
        }
        if (!AdMobManager.isInterstitialLoaded(AdMobManager.InterstitialPlacement.SEARCH_BACK)) {
            complete()
            return
        }
        val shown = AdMobManager.showInterstitial(
            this,
            AdMobManager.InterstitialPlacement.SEARCH_BACK,
            object : AdShowListener {
                override fun onAdShowed() = Unit
                override fun onAdShowFailed(error: String) = complete()
                override fun onAdClosed() = complete()
                override fun onAdClicked() = Unit
            },
        )
        if (!shown) complete()
    }

    private fun observeNativeAd() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var forceRefresh = true
                while (true) {
                    val loading = AdMobManager.isNativeAdLoading(AdMobManager.NativePlacement.HOME)
                    val state = AdMobManager.updateNativeAdContainer(
                        binding.nativeAd,
                        AdMobManager.NativePlacement.HOME,
                        loadWhenMissing = true,
                        preloadAfterDisplay = true,
                        forceRefresh = forceRefresh,
                    )
                    forceRefresh = false
                    delay(
                        if (state == AdMobManager.NativeContainerState.LOADING && loading) {
                            NATIVE_AD_POLL_INTERVAL_MS
                        } else {
                            NATIVE_AD_IDLE_POLL_INTERVAL_MS
                        },
                    )
                }
            }
        }
    }

    private fun onRefreshClicked() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAtMs < REFRESH_DEBOUNCE_MS) return
        lastRefreshAtMs = now
        animateRefreshIcon()
        if (keywords.isEmpty()) {
            loadTrending(forceNetwork = true, showLoading = true)
            return
        }
        pageIndex += 1
        if (pageIndex >= repository.pageCount(keywords.size)) {
            pageIndex = 0
        }
        renderKeywords()
    }

    private fun loadTrending(forceNetwork: Boolean, showLoading: Boolean) {
        if (refreshingNetwork) return
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            if (showLoading && keywords.isEmpty()) {
                binding.popularLoading.visibility = View.VISIBLE
            }
            val first = if (forceNetwork) {
                refreshingNetwork = true
                try {
                    repository.refreshNetwork(this@SearchActivity)
                } finally {
                    refreshingNetwork = false
                }
            } else {
                repository.load(this@SearchActivity, forceNetwork = false)
            }
            applyResult(first)
            if (first.stale || (first.loading && !forceNetwork)) {
                if (refreshingNetwork) return@launch
                refreshingNetwork = true
                if (showLoading) {
                    binding.popularLoading.visibility = View.VISIBLE
                }
                try {
                    val fresh = repository.refreshNetwork(this@SearchActivity)
                    applyResult(fresh, resetPage = true)
                } finally {
                    refreshingNetwork = false
                    if (showLoading) {
                        binding.popularLoading.visibility = View.GONE
                    }
                }
            } else {
                if (showLoading) {
                    binding.popularLoading.visibility = View.GONE
                }
            }
        }
    }

    private fun applyResult(result: TrendingRepository.TrendingLoadResult, resetPage: Boolean = false) {
        keywords = result.keywords
        if (resetPage || pageIndex >= repository.pageCount(keywords.size)) {
            pageIndex = 0
        }
        renderKeywords()
        if (!result.loading || !keywords.isEmpty()) {
            binding.popularLoading.visibility = View.GONE
        }
    }

    private fun renderKeywords() {
        val page = repository.page(keywords, pageIndex)
        val grid = binding.popularGrid
        grid.removeAllViews()
        val columns = 2
        val rows = 3
        repeat(rows * columns) { index ->
            val keyword = page.getOrNull(index).orEmpty()
            val cell = TextView(this).apply {
                text = keyword
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(KEYWORD_COLOR)
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@SearchActivity, R.font.inter_regular)
                setPadding(0, dp(10), dp(12), dp(10))
                isClickable = keyword.isNotEmpty()
                isFocusable = keyword.isNotEmpty()
                if (keyword.isNotEmpty()) {
                    setOnClickListener { submitKeyword(keyword) }
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % columns, 1f)
                rowSpec = GridLayout.spec(index / columns)
                setMargins(0, 0, 0, 0)
            }
            grid.addView(cell, params)
        }
    }

    private fun setHistoryMode(mode: HistoryMode) {
        val changed = historyMode != mode
        historyMode = mode
        renderHistory(resetScroll = changed)
    }

    private fun renderHistory(resetScroll: Boolean = false) {
        if (BrowserSettingsStore.isSeamlessEnabled(this) || historyItems.isEmpty()) {
            historyMode = HistoryMode.COLLAPSED
            binding.historySection.visibility = View.GONE
            binding.historyScroll.maxHeightPx = 0
            return
        }
        binding.historySection.visibility = View.VISIBLE
        binding.historyExpandedBar.visibility =
            if (historyMode == HistoryMode.EXPANDED) View.VISIBLE else View.GONE
        binding.historyManageBar.visibility =
            if (historyMode == HistoryMode.MANAGING) View.VISIBLE else View.GONE
        binding.historyScroll.maxHeightPx =
            if (historyMode == HistoryMode.COLLAPSED) 0 else dp(HISTORY_SCROLL_MAX_DP)
        if (resetScroll) binding.historyScroll.scrollTo(0, 0)
        bindHistoryGrid()
    }

    private fun bindHistoryGrid() {
        val grid = binding.historyGrid
        grid.removeAllViews()
        val collapsed = historyMode == HistoryMode.COLLAPSED
        val managing = historyMode == HistoryMode.MANAGING
        val shown = if (collapsed) historyItems.take(SearchHistoryStore.COLLAPSED_COUNT) else historyItems
        shown.forEachIndexed { index, keyword ->
            grid.addView(historyItemView(keyword, managing), historyCellParams(index))
        }
        if (collapsed) {
            grid.addView(moreHistoryCell(), historyCellParams(shown.size))
        }
    }

    private fun historyItemView(keyword: String, managing: Boolean): View {
        val label = TextView(this).apply {
            text = keyword
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(KEYWORD_COLOR)
            textSize = 14f
            typeface = ResourcesCompat.getFont(this@SearchActivity, R.font.inter_regular)
            if (!managing) {
                isClickable = true
                isFocusable = true
                setOnClickListener { submitKeyword(keyword) }
            }
        }
        if (!managing) {
            label.setPadding(0, dp(10), dp(12), dp(10))
            return label
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_search_x)
                contentDescription = getString(R.string.search_history_delete_item)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val size = dp(28)
                layoutParams = LinearLayout.LayoutParams(size, size)
                isClickable = true
                isFocusable = true
                setOnClickListener { deleteHistoryItem(keyword) }
            },
        )
        return row
    }

    private fun moreHistoryCell(): View {
        val more = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { setHistoryMode(HistoryMode.EXPANDED) }
        }
        more.addView(
            TextView(this).apply {
                text = getString(R.string.search_history_more)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(SECONDARY_COLOR)
                textSize = 13f
                typeface = ResourcesCompat.getFont(this@SearchActivity, R.font.inter_regular)
            },
        )
        more.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_search_exp)
                contentDescription = getString(R.string.search_history_more)
                val size = dp(12)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(4)
                }
            },
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(
            more,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        row.addView(
            View(this),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        row.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_search_delete)
                contentDescription = getString(R.string.search_history_delete)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val size = dp(28)
                layoutParams = LinearLayout.LayoutParams(size, size)
                isClickable = true
                isFocusable = true
                setOnClickListener { setHistoryMode(HistoryMode.MANAGING) }
            },
        )
        return row
    }

    private fun historyCellParams(index: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(index % 2, 1f)
            rowSpec = GridLayout.spec(index / 2)
            setMargins(0, 0, 0, 0)
        }

    private fun deleteHistoryItem(keyword: String) {
        historyItems = historyStore.remove(keyword)
        if (historyItems.isEmpty()) historyMode = HistoryMode.COLLAPSED
        renderHistory()
    }

    private fun confirmDeleteAllHistory() {
        deleteAllSheet?.dismiss()
        val dialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetHistoryDeleteBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        dialog.setCancelable(true)
        dialog.behavior.isDraggable = true
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        sheetBinding.tvDeleteMessage.setText(R.string.search_history_delete_all_confirm)
        sheetBinding.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            historyItems = historyStore.clear()
            renderHistory()
        }
        sheetBinding.btnCancelDelete.setOnClickListener { dialog.dismiss() }
        deleteAllSheet = dialog
        dialog.show()
    }

    private fun submitKeyword(keyword: String) {
        binding.searchInput.setText(keyword)
        binding.searchInput.setSelection(keyword.length)
        submitSearch()
    }

    private fun submitSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        if (!BrowserSettingsStore.isSeamlessEnabled(this)) {
            historyStore.add(query)
        }
        hideKeyboard(binding.searchInput)
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_QUERY, query),
        )
        finish()
    }

    private fun animateRefreshIcon() {
        binding.btnRefresh.animate().cancel()
        binding.btnRefresh.rotation = 0f
        binding.btnRefresh.animate()
            .rotationBy(360f)
            .setDuration(420L)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private enum class HistoryMode { COLLAPSED, EXPANDED, MANAGING }

    companion object {
        private const val EXTRA_QUERY = "search_query"
        private const val STATE_PAGE = "page_index"
        private const val STATE_HISTORY_MODE = "history_mode"
        private const val REFRESH_DEBOUNCE_MS = 350L
        private const val HISTORY_SCROLL_MAX_DP = 220
        private const val NATIVE_AD_POLL_INTERVAL_MS = 300L
        private const val NATIVE_AD_IDLE_POLL_INTERVAL_MS = 2_000L
        private val KEYWORD_COLOR = Color.parseColor("#FF3A4458")
        private val SECONDARY_COLOR = Color.parseColor("#FF6B7480")

        fun createIntent(context: Context): Intent =
            Intent(context, SearchActivity::class.java)

        fun readQuery(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    var maxHeightPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSpec = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val canScroll = canScrollVertically(1) || canScrollVertically(-1)
                parent?.requestDisallowInterceptTouchEvent(canScroll)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(ev)
    }
}
