package com.anony.bro.wser.view.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import com.google.android.material.card.MaterialCardView
import com.anony.bro.wser.R
import com.anony.bro.wser.data.tab.PersistedTab
import com.anony.bro.wser.data.tab.TabLoadState
import com.anony.bro.wser.data.tab.TabSessionRepository
import com.anony.bro.wser.data.tab.TabSessionSnapshot
import com.anony.bro.wser.data.settings.AppLanguageStore
import com.anony.bro.wser.databinding.ActivityTabListBinding
import com.anony.bro.wser.databinding.PopupTabListMenuBinding
import java.util.UUID

class TabListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTabListBinding
    private val tabs = mutableListOf<TabItem>()
    private val selectedIds = linkedSetOf<String>()
    private var selectedTabId: String? = null
    private var selectionMode = false
    private var menuPopup: PopupWindow? = null
    private val tabSessionRepository by lazy { TabSessionRepository.get(this) }

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
        binding = ActivityTabListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        readTabs(savedInstanceState)
        setupActions()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithResult(openSelected = false)
            }
        })
        binding.tabsGrid.post { renderTabs() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_IDS, ArrayList(tabs.map { it.id }))
        outState.putStringArrayList(STATE_TITLES, ArrayList(tabs.map { it.title }))
        outState.putStringArrayList(STATE_URLS, ArrayList(tabs.map { it.url.orEmpty() }))
        outState.putStringArrayList(STATE_THUMBNAILS, ArrayList(tabs.map { it.thumbnailPath.orEmpty() }))
        outState.putStringArrayList(STATE_FAVICONS, ArrayList(tabs.map { it.faviconPath.orEmpty() }))
        outState.putStringArrayList(STATE_LOAD_STATES, ArrayList(tabs.map { it.loadState.name }))
        outState.putStringArrayList(STATE_SELECTED_IDS, ArrayList(selectedIds))
        outState.putString(STATE_SELECTED_TAB_ID, selectedTabId)
        outState.putBoolean(STATE_SELECTION_MODE, selectionMode)
        persistTabsAsync()
    }

    override fun onStop() {
        persistTabsBlocking()
        super.onStop()
    }

    override fun finish() {
        menuPopup?.dismiss()
        persistTabsAsync()
        super.finish()
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.tabListRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = systemBars.top)
            binding.topBar.layoutParams = binding.topBar.layoutParams.apply {
                height = dp(56) + systemBars.top
            }
            binding.bottomBar.updatePadding(bottom = systemBars.bottom)
            binding.bottomBar.layoutParams = binding.bottomBar.layoutParams.apply {
                height = dp(56) + systemBars.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.tabListRoot)
    }

    private fun readTabs(savedInstanceState: Bundle?) {
        val ids = savedInstanceState?.getStringArrayList(STATE_IDS)
            ?: intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
        val titles = savedInstanceState?.getStringArrayList(STATE_TITLES)
            ?: intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val urls = savedInstanceState?.getStringArrayList(STATE_URLS)
            ?: intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        val thumbnails = savedInstanceState?.getStringArrayList(STATE_THUMBNAILS)
            ?: intent.getStringArrayListExtra(EXTRA_THUMBNAILS).orEmpty()
        val favicons = savedInstanceState?.getStringArrayList(STATE_FAVICONS)
            ?: intent.getStringArrayListExtra(EXTRA_FAVICONS).orEmpty()
        val loadStates = savedInstanceState?.getStringArrayList(STATE_LOAD_STATES)
            ?: intent.getStringArrayListExtra(EXTRA_LOAD_STATES)
            ?: ArrayList()
        // 兼容旧版 boolean loadings
        val legacyLoadings = intent.getBooleanArrayExtra(EXTRA_LOADINGS)
        selectedTabId = savedInstanceState?.getString(STATE_SELECTED_TAB_ID)
            ?: intent.getStringExtra(EXTRA_SELECTED_ID)
        selectionMode = savedInstanceState?.getBoolean(STATE_SELECTION_MODE) ?: false
        selectedIds.clear()
        selectedIds += savedInstanceState?.getStringArrayList(STATE_SELECTED_IDS).orEmpty()
        ids.forEachIndexed { index, id ->
            val url = urls.getOrNull(index).orEmpty().takeIf { it.isNotBlank() }
            val loadState = loadStates.getOrNull(index)?.let(TabLoadState::parse)
                ?: TabLoadState.from(
                    isLoading = legacyLoadings?.getOrNull(index) == true,
                    hasError = false,
                    hasUrl = url != null,
                )
            tabs += TabItem(
                id = id,
                title = if (url == null) {
                    getString(R.string.browser_new_tab)
                } else {
                    titles.getOrNull(index).orEmpty()
                },
                url = url,
                thumbnailPath = thumbnails.getOrNull(index).orEmpty().takeIf { it.isNotBlank() },
                faviconPath = favicons.getOrNull(index).orEmpty().takeIf { it.isNotBlank() },
                loadState = loadState,
            )
        }
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener {
            finishWithResult(openSelected = false)
        }
        binding.btnBottomAction.setOnClickListener {
            if (selectionMode) {
                closeSelectedTabs()
            } else {
                createNewTab()
            }
        }
        binding.btnMore.setOnClickListener {
            showTabsMenu()
        }
    }

    private fun renderTabs() {
        binding.tabsGrid.removeAllViews()
        binding.tabsScroll.visibility = if (tabs.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyState.visibility = if (tabs.isEmpty()) View.VISIBLE else View.GONE
        tabs.forEachIndexed { index, tab ->
            binding.tabsGrid.addView(createTabCard(tab), cardLayoutParams(index))
        }
        renderBottomBar()
    }

    private fun createTabCard(tab: TabItem): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(Color.WHITE)
            radius = dp(12).toFloat()
            cardElevation = dp(4).toFloat()
            strokeWidth = 0
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selectionMode) {
                    toggleSelected(tab.id)
                } else {
                    selectedTabId = tab.id
                    finishWithResult(openSelected = true)
                }
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(createCardHeader(tab))
        content.addView(createPreview(tab))
        card.addView(content)
        return card
    }

    private fun createCardHeader(tab: TabItem): View {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(4), 0)
        }
        header.addView(ImageView(this).apply {
            setTabFavicon(tab)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = siteName(tab)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        header.addView(TextView(this).apply {
            text = siteName(tab)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            textSize = 13f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        header.addView(ImageButton(this).apply {
            setImageResource(actionIconFor(tab))
            background = null
            contentDescription = getString(R.string.browser_close_tab)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                if (selectionMode) {
                    toggleSelected(tab.id)
                } else {
                    closeTab(tab.id)
                }
            }
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        return header.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40),
            )
        }
    }

    private fun createPreview(tab: TabItem): View {
        val preview = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_tab_preview)
            clipToOutline = true
        }
        val thumbnail = tab.thumbnailPath
            ?.let { BitmapFactory.decodeFile(it) }
        if (thumbnail != null) {
            preview.addView(ImageView(this).apply {
                setImageBitmap(thumbnail)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        } else {
            preview.addView(createPreviewPlaceholder(tab), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        return preview.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(168),
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                bottomMargin = dp(8)
            }
        }
    }

    private fun createPreviewPlaceholder(tab: TabItem): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            addView(TextView(this@TabListActivity).apply {
                text = siteName(tab).takeIf { it.isNotBlank() } ?: getString(R.string.browser_new_tab)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                textSize = 14f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
            })
            addView(TextView(this@TabListActivity).apply {
                text = tab.url?.let(::hostFromUrl) ?: getString(R.string.browser_search_hint)
                setTextColor(0xFF8A9099.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                setBackgroundResource(R.drawable.bg_tab_preview_chip)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            })
        }
    }

    private fun cardLayoutParams(index: Int): GridLayout.LayoutParams {
        val gridWidth = binding.tabsGrid.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(32))
        val gutter = dp(12)
        val cardWidth = ((gridWidth - gutter) / 2).coerceAtLeast(dp(120))
        return GridLayout.LayoutParams().apply {
            width = cardWidth
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            bottomMargin = dp(12)
            if (index % 2 == 0) {
                if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    leftMargin = gutter
                } else {
                    rightMargin = gutter
                }
            }
        }
    }

    private fun showTabsMenu() {
        menuPopup?.dismiss()
        val menuBinding = PopupTabListMenuBinding.inflate(layoutInflater)
        val popupWidth = dp(188)
        menuBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = menuBinding.root.measuredHeight
        menuPopup = PopupWindow(
            menuBinding.root,
            popupWidth,
            popupHeight,
            true,
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isClippingEnabled = false
        }
        menuBinding.menuSelectTabs.setOnClickListener {
            menuPopup?.dismiss()
            selectionMode = true
            selectedIds.clear()
            renderTabs()
        }
        menuBinding.menuCloseAllTabs.setOnClickListener {
            menuPopup?.dismiss()
            tabs.clear()
            selectedIds.clear()
            selectedTabId = null
            selectionMode = false
            renderTabs()
            persistTabsAsync()
        }
        val anchorLocation = IntArray(2)
        binding.btnMore.getLocationInWindow(anchorLocation)
        val x = binding.root.width - popupWidth - dp(21)
        val y = (anchorLocation[1] - popupHeight - dp(8)).coerceAtLeast(0)
        menuPopup?.showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
    }

    private fun createNewTab() {
        val id = UUID.randomUUID().toString()
        tabs += TabItem(
            id = id,
            title = getString(R.string.browser_new_tab),
            url = null,
            thumbnailPath = null,
            faviconPath = null,
            loadState = TabLoadState.IDLE,
        )
        selectedTabId = id
        finishWithResult(openSelected = true)
    }

    private fun closeTab(tabId: String) {
        tabs.removeAll { it.id == tabId }
        selectedIds.remove(tabId)
        if (selectedTabId == tabId) {
            selectedTabId = tabs.firstOrNull()?.id
        }
        renderTabs()
        persistTabsAsync()
    }

    private fun closeSelectedTabs() {
        if (selectedIds.isEmpty()) return
        tabs.removeAll { it.id in selectedIds }
        if (selectedTabId in selectedIds) {
            selectedTabId = tabs.firstOrNull()?.id
        }
        selectedIds.clear()
        selectionMode = false
        renderTabs()
        persistTabsAsync()
    }

    private fun toggleSelected(tabId: String) {
        if (!selectedIds.add(tabId)) {
            selectedIds.remove(tabId)
        }
        renderTabs()
    }

    private fun renderBottomBar() {
        if (selectionMode) {
            binding.ivBottomAction.setImageResource(R.drawable.ic_close_tab)
            binding.ivBottomAction.contentDescription = getString(R.string.browser_close_tab)
            binding.tvBottomAction.text = getString(R.string.tabs_selected_count, selectedIds.size)
            binding.btnMore.visibility = View.GONE
        } else {
            binding.ivBottomAction.setImageResource(R.drawable.ic_tab_new_tab)
            binding.ivBottomAction.contentDescription = getString(R.string.tabs_new_tab)
            binding.tvBottomAction.setText(R.string.tabs_new_tab)
            binding.btnMore.visibility = View.VISIBLE
        }
    }

    private fun actionIconFor(tab: TabItem): Int {
        return if (!selectionMode) {
            R.drawable.ic_tab_x
        } else if (tab.id in selectedIds) {
            R.drawable.ic_check
        } else {
            R.drawable.ic_dis_check
        }
    }

    private fun ImageView.setTabFavicon(tab: TabItem) {
        val favicon = tab.faviconPath
            ?.let { BitmapFactory.decodeFile(it) }
        when {
            favicon != null -> setImageBitmap(favicon)
            tab.url?.contains("youtube", ignoreCase = true) == true -> setImageResource(R.drawable.ic_youtube)
            else -> setImageResource(R.drawable.ic_security)
        }
    }

    private fun siteName(tab: TabItem): String {
        if (tab.url.isNullOrBlank()) return getString(R.string.browser_new_tab)
        val title = tab.title.trim()
        if (title.isNotBlank()) return title
        return tab.url.let(::hostFromUrl).takeIf { it.isNotBlank() }
            ?: getString(R.string.browser_new_tab)
    }

    private fun hostFromUrl(url: String): String {
        return runCatching {
            Uri.parse(url).host
                ?.removePrefix("www.")
                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }.getOrNull() ?: url
    }

    private fun finishWithResult(openSelected: Boolean) {
        persistTabsBlocking()
        setResult(Activity.RESULT_OK, Intent().apply {
            putStringArrayListExtra(EXTRA_IDS, ArrayList(tabs.map { it.id }))
            putStringArrayListExtra(EXTRA_TITLES, ArrayList(tabs.map { it.title }))
            putStringArrayListExtra(EXTRA_URLS, ArrayList(tabs.map { it.url.orEmpty() }))
            putStringArrayListExtra(EXTRA_LOAD_STATES, ArrayList(tabs.map { it.loadState.name }))
            putExtra(EXTRA_LOADINGS, tabs.map { it.loadState.isLoading }.toBooleanArray())
            putExtra(EXTRA_SELECTED_ID, selectedTabId)
            putExtra(EXTRA_OPEN_SELECTED, openSelected)
        })
        finish()
    }

    private fun buildSessionSnapshot(): TabSessionSnapshot {
        val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
        return TabSessionSnapshot(
            selectedTabId = selectedTabId,
            selectedTabIndex = selectedIndex,
            showingHome = true,
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class TabItem(
        val id: String,
        val title: String,
        val url: String?,
        val thumbnailPath: String?,
        val faviconPath: String?,
        val loadState: TabLoadState,
    )

    companion object {
        private const val TAG = "TabListActivity"
        private const val EXTRA_IDS = "tab_ids"
        private const val EXTRA_TITLES = "tab_titles"
        private const val EXTRA_URLS = "tab_urls"
        private const val EXTRA_LOADINGS = "tab_loadings"
        private const val EXTRA_LOAD_STATES = "tab_load_states"
        private const val EXTRA_THUMBNAILS = "tab_thumbnails"
        private const val EXTRA_FAVICONS = "tab_favicons"
        private const val EXTRA_SELECTED_ID = "selected_tab_id"
        private const val EXTRA_OPEN_SELECTED = "open_selected"
        private const val STATE_IDS = "state_tab_ids"
        private const val STATE_TITLES = "state_tab_titles"
        private const val STATE_URLS = "state_tab_urls"
        private const val STATE_LOAD_STATES = "state_tab_load_states"
        private const val STATE_THUMBNAILS = "state_tab_thumbnails"
        private const val STATE_FAVICONS = "state_tab_favicons"
        private const val STATE_SELECTED_IDS = "state_selected_ids"
        private const val STATE_SELECTED_TAB_ID = "state_selected_tab_id"
        private const val STATE_SELECTION_MODE = "state_selection_mode"

        fun createIntent(
            context: Context,
            ids: ArrayList<String>,
            titles: ArrayList<String>,
            urls: ArrayList<String>,
            loadStates: ArrayList<String>,
            thumbnails: ArrayList<String>,
            favicons: ArrayList<String>,
            selectedTabId: String?,
        ): Intent = Intent(context, TabListActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_IDS, ids)
            putStringArrayListExtra(EXTRA_TITLES, titles)
            putStringArrayListExtra(EXTRA_URLS, urls)
            putStringArrayListExtra(EXTRA_LOAD_STATES, loadStates)
            putExtra(EXTRA_LOADINGS, loadStates.map { TabLoadState.parse(it).isLoading }.toBooleanArray())
            putStringArrayListExtra(EXTRA_THUMBNAILS, thumbnails)
            putStringArrayListExtra(EXTRA_FAVICONS, favicons)
            putExtra(EXTRA_SELECTED_ID, selectedTabId)
        }

        fun readIds(intent: Intent): List<String> =
            intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()

        fun readTitles(intent: Intent): List<String> =
            intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()

        fun readUrls(intent: Intent): List<String> =
            intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()

        fun readLoadStates(intent: Intent): List<String> {
            val states = intent.getStringArrayListExtra(EXTRA_LOAD_STATES)
            if (!states.isNullOrEmpty()) return states
            val loadings = intent.getBooleanArrayExtra(EXTRA_LOADINGS)
            val ids = readIds(intent)
            return ids.mapIndexed { index, _ ->
                if (loadings?.getOrNull(index) == true) {
                    TabLoadState.LOADING.name
                } else {
                    val url = readUrls(intent).getOrNull(index)
                    if (url.isNullOrBlank()) TabLoadState.IDLE.name else TabLoadState.COMPLETED.name
                }
            }
        }

        fun readSelectedId(intent: Intent): String? =
            intent.getStringExtra(EXTRA_SELECTED_ID)

        fun shouldOpenSelected(intent: Intent): Boolean =
            intent.getBooleanExtra(EXTRA_OPEN_SELECTED, false)
    }
}
