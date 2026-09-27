package com.anony.bro.wser.view.main

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.anony.bro.wser.R
import com.anony.bro.wser.data.history.HistoryEntry
import com.anony.bro.wser.data.history.HistoryRepository
import com.anony.bro.wser.data.settings.AppLanguageStore
import com.anony.bro.wser.databinding.ActivityHistoryBinding
import com.anony.bro.wser.databinding.BottomSheetHistoryDeleteBinding
import com.anony.bro.wser.databinding.PopupHistoryItemMenuBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val historyRepository by lazy { HistoryRepository.get(this) }
    private val entries = mutableListOf<HistoryEntry>()
    private val selectedIds = linkedSetOf<String>()
    private val collapsedGroups = mutableSetOf<HistoryGroupType>()
    private var selectionMode = false
    private var itemMenuPopup: PopupWindow? = null
    private var deleteSheet: BottomSheetDialog? = null
    private val loadingFaviconKeys = mutableSetOf<String>()
    private val faviconImageViews = mutableMapOf<String, MutableList<ImageView>>()

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
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        restoreState(savedInstanceState)
        loadEntries()
        setupActions()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionMode) {
                    exitSelectionMode()
                } else {
                    finish()
                }
            }
        })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SELECTION_MODE, selectionMode)
        outState.putStringArrayList(STATE_SELECTED_IDS, ArrayList(selectedIds))
        outState.putStringArrayList(
            STATE_COLLAPSED_GROUPS,
            ArrayList(collapsedGroups.map { it.name }),
        )
    }

    override fun finish() {
        itemMenuPopup?.dismiss()
        deleteSheet?.dismiss()
        super.finish()
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.WHITE
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.historyRoot) { _, insets ->
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
        ViewCompat.requestApplyInsets(binding.historyRoot)
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        selectionMode = savedInstanceState?.getBoolean(STATE_SELECTION_MODE) ?: false
        selectedIds.clear()
        selectedIds += savedInstanceState?.getStringArrayList(STATE_SELECTED_IDS).orEmpty()
        collapsedGroups.clear()
        if (savedInstanceState != null) {
            savedInstanceState.getStringArrayList(STATE_COLLAPSED_GROUPS)
                ?.mapNotNull { runCatching { HistoryGroupType.valueOf(it) }.getOrNull() }
                ?.let { collapsedGroups += it }
        } else {
            // 默认展开当天，收起昨天与更早
            collapsedGroups += HistoryGroupType.YESTERDAY
            collapsedGroups += HistoryGroupType.EARLIER
        }
    }

    private fun loadEntries() {
        entries.clear()
        entries += historyRepository.loadBlocking()
            .sortedByDescending { it.visitedAt }
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSelect.setOnClickListener {
            if (selectionMode) {
                exitSelectionMode()
            } else {
                enterSelectionMode()
            }
        }
        binding.btnDeleteSelected.setOnClickListener {
            if (selectedIds.isNotEmpty()) {
                showDeleteConfirmSheet()
            }
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectedIds.clear()
        itemMenuPopup?.dismiss()
        render()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        render()
    }

    private fun render() {
        faviconImageViews.clear()
        binding.historyList.removeAllViews()
        val groups = buildGroups()
        val hasItems = groups.any { it.entries.isNotEmpty() }
        binding.historyScroll.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (hasItems) View.GONE else View.VISIBLE
        groups.filter { it.entries.isNotEmpty() }.forEachIndexed { index, group ->
            binding.historyList.addView(
                createGroupCard(group),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) topMargin = dp(12)
                },
            )
        }
        renderBottomBar()
    }

    private fun buildGroups(): List<HistoryGroup> {
        val zone = TimeZone.getDefault()
        val todayStart = startOfDayMillis(0, zone)
        val yesterdayStart = startOfDayMillis(-1, zone)
        val today = mutableListOf<HistoryEntry>()
        val yesterday = mutableListOf<HistoryEntry>()
        val earlier = mutableListOf<HistoryEntry>()
        entries.forEach { entry ->
            when {
                entry.visitedAt >= todayStart -> today += entry
                entry.visitedAt >= yesterdayStart -> yesterday += entry
                else -> earlier += entry
            }
        }
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        return listOf(
            HistoryGroup(
                type = HistoryGroupType.TODAY,
                title = getString(
                    R.string.history_group_today,
                    dateFormat.format(Date(todayStart)).uppercase(Locale.US),
                ),
                entries = today,
            ),
            HistoryGroup(
                type = HistoryGroupType.YESTERDAY,
                title = getString(
                    R.string.history_group_yesterday,
                    dateFormat.format(Date(yesterdayStart)).uppercase(Locale.US),
                ),
                entries = yesterday,
            ),
            HistoryGroup(
                type = HistoryGroupType.EARLIER,
                title = getString(R.string.history_group_earlier),
                entries = earlier,
            ),
        )
    }

    private fun createGroupCard(group: HistoryGroup): View {
        val expanded = group.type !in collapsedGroups
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(Color.WHITE)
            radius = dp(16).toFloat()
            cardElevation = dp(3).toFloat()
            strokeWidth = 0
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(createGroupHeader(group, expanded))
        if (expanded) {
            group.entries.forEach { entry ->
                content.addView(createHistoryRow(entry))
            }
        }
        card.addView(content)
        return card
    }

    private fun createGroupHeader(group: HistoryGroup, expanded: Boolean): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), if (expanded) dp(6) else dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleGroup(group.type) }
        }
        header.addView(
            TextView(this).apply {
                text = group.title
                setTextColor(0xFF1E4D39.toInt())
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                letterSpacing = 0.02f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_stow)
                rotation = if (expanded) 0f else 180f
                contentDescription = getString(
                    if (expanded) R.string.history_collapse else R.string.history_expand,
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            LinearLayout.LayoutParams(dp(28), dp(28)),
        )
        return header
    }

    private fun createHistoryRow(entry: HistoryEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selectionMode) {
                    toggleSelected(entry.id)
                } else {
                    openInCurrentTab(entry)
                }
            }
        }
        row.addView(
            ImageView(this).apply {
                setHistoryFavicon(entry)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                contentDescription = entry.title
            },
            LinearLayout.LayoutParams(dp(28), dp(28)),
        )
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        textColumn.addView(
            TextView(this).apply {
                text = entry.title.ifBlank { entry.url }
                setTextColor(0xFF1E4D39.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        textColumn.addView(
            TextView(this).apply {
                text = entry.url
                setTextColor(0xFF828896.toInt())
                textSize = 12f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) },
        )
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            ImageButton(this).apply {
                background = null
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                if (selectionMode) {
                    val selected = entry.id in selectedIds
                    setImageResource(if (selected) R.drawable.ic_check else R.drawable.ic_dis_check)
                    contentDescription = getString(R.string.history_select)
                    setOnClickListener { toggleSelected(entry.id) }
                } else {
                    setImageResource(R.drawable.ic_net_menu)
                    contentDescription = getString(R.string.browser_menu)
                    setOnClickListener { showItemMenu(it, entry) }
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        return row
    }

    private fun toggleGroup(type: HistoryGroupType) {
        if (!collapsedGroups.add(type)) {
            collapsedGroups.remove(type)
        }
        render()
    }

    private fun toggleSelected(id: String) {
        if (!selectedIds.add(id)) {
            selectedIds.remove(id)
        }
        render()
    }

    private fun renderBottomBar() {
        if (selectionMode) {
            binding.ivSelect.setImageResource(R.drawable.ic_close_tab)
            binding.ivSelect.contentDescription = getString(R.string.browser_cancel)
            binding.tvSelect.text = getString(R.string.history_select_count, selectedIds.size)
            binding.btnDeleteSelected.visibility = View.VISIBLE
        } else {
            binding.ivSelect.setImageResource(R.drawable.ic_tab_select_tabs)
            binding.ivSelect.contentDescription = getString(R.string.history_select)
            binding.tvSelect.setText(R.string.history_select)
            binding.btnDeleteSelected.visibility = View.GONE
        }
    }

    private fun showItemMenu(anchor: View, entry: HistoryEntry) {
        itemMenuPopup?.dismiss()
        val menuBinding = PopupHistoryItemMenuBinding.inflate(layoutInflater)
        val popupWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt().coerceAtLeast(dp(180))
        menuBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = menuBinding.root.measuredHeight
        itemMenuPopup = PopupWindow(
            menuBinding.root,
            popupWidth,
            popupHeight,
            true,
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isClippingEnabled = true
        }
        menuBinding.menuOpenNewTab.setOnClickListener {
            itemMenuPopup?.dismiss()
            openInNewTab(entry)
        }
        menuBinding.menuCopyLink.setOnClickListener {
            itemMenuPopup?.dismiss()
            copyLink(entry.url)
        }
        menuBinding.menuShare.setOnClickListener {
            itemMenuPopup?.dismiss()
            shareLink(entry)
        }
        menuBinding.menuDelete.setOnClickListener {
            itemMenuPopup?.dismiss()
            deleteEntries(listOf(entry.id))
        }

        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val topBarBottom = IntArray(2).also { binding.topBar.getLocationInWindow(it) }[1] +
            binding.topBar.height
        var x = (anchorLocation[0] + anchor.width - popupWidth).coerceAtLeast(dp(12))
        x = x.coerceAtMost(binding.root.width - popupWidth - dp(12))
        var y = anchorLocation[1] + anchor.height + dp(4)
        if (y + popupHeight > binding.root.height - dp(8)) {
            y = (anchorLocation[1] - popupHeight - dp(4)).coerceAtLeast(topBarBottom + dp(4))
        }
        if (y < topBarBottom) {
            y = topBarBottom + dp(4)
        }
        itemMenuPopup?.showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
    }

    private fun showDeleteConfirmSheet() {
        deleteSheet?.dismiss()
        val dialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetHistoryDeleteBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        dialog.setCancelable(true)
        dialog.behavior.isDraggable = true
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        sheetBinding.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            deleteEntries(selectedIds.toList())
            exitSelectionMode()
        }
        sheetBinding.btnCancelDelete.setOnClickListener { dialog.dismiss() }
        deleteSheet = dialog
        dialog.show()
    }

    private fun deleteEntries(ids: Collection<String>) {
        if (ids.isEmpty()) return
        historyRepository.deleteByIdsBlocking(ids)
        entries.removeAll { it.id in ids }
        selectedIds.removeAll(ids.toSet())
        render()
    }

    private fun openInCurrentTab(entry: HistoryEntry) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_URL, entry.url)
                putExtra(EXTRA_OPEN_IN_NEW_TAB, false)
            },
        )
        finish()
    }

    private fun openInNewTab(entry: HistoryEntry) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_URL, entry.url)
                putExtra(EXTRA_OPEN_IN_NEW_TAB, true)
            },
        )
        finish()
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, R.string.history_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink(entry: HistoryEntry) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, entry.url)
            putExtra(Intent.EXTRA_SUBJECT, entry.title)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.browser_share)))
    }

    private fun ImageView.setHistoryFavicon(entry: HistoryEntry) {
        val target = faviconTarget(entry.url)
        if (target == null) {
            tag = null
            setImageResource(R.drawable.ic_security)
            return
        }

        tag = target.key
        faviconImageViews.getOrPut(target.key) { mutableListOf() }.add(this)
        val cachedBitmap = decodeCachedFavicon(target.file)
        if (cachedBitmap != null) {
            setImageBitmap(cachedBitmap)
            return
        }

        setImageResource(R.drawable.ic_security)
        loadHistoryFavicon(target)
    }

    private fun loadHistoryFavicon(target: FaviconTarget) {
        if (!loadingFaviconKeys.add(target.key)) return
        lifecycleScope.launch {
            val faviconPath = try {
                withContext(Dispatchers.IO) {
                    fetchAndCacheFavicon(target)
                }
            } finally {
                loadingFaviconKeys.remove(target.key)
            }
            val bitmap = faviconPath?.let(BitmapFactory::decodeFile) ?: return@launch
            faviconImageViews[target.key].orEmpty()
                .filter { it.tag == target.key }
                .forEach { it.setImageBitmap(bitmap) }
        }
    }

    private fun faviconTarget(rawUrl: String): FaviconTarget? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)?.takeIf { it == "http" || it == "https" }
            ?: "https"
        val key = host.replace(Regex("[^a-z0-9._-]"), "_")
        val file = File(filesDir, "history_favicons/$key.png")
        return FaviconTarget(
            key = key,
            scheme = scheme,
            host = host,
            file = file,
        )
    }

    private fun decodeCachedFavicon(file: File): Bitmap? {
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            runCatching { file.delete() }
        }
        return bitmap
    }

    private fun fetchAndCacheFavicon(target: FaviconTarget): String? {
        decodeCachedFavicon(target.file)?.let { bitmap ->
            bitmap.recycle()
            return target.file.absolutePath
        }
        val candidates = listOf(
            "${target.scheme}://${target.host}/favicon.ico",
            "https://www.google.com/s2/favicons?domain=${target.host}&sz=128",
        )
        val bitmap = candidates.firstNotNullOfOrNull { candidate ->
            runCatching { downloadBitmap(candidate) }.getOrNull()
        } ?: return null
        target.file.parentFile?.mkdirs()
        FileOutputStream(target.file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return target.file.absolutePath
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
        } finally {
            connection.disconnect()
        }
    }

    private fun startOfDayMillis(dayOffset: Int, zone: TimeZone): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.DAY_OF_YEAR, dayOffset)
        return calendar.timeInMillis
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class HistoryGroup(
        val type: HistoryGroupType,
        val title: String,
        val entries: List<HistoryEntry>,
    )

    private data class FaviconTarget(
        val key: String,
        val scheme: String,
        val host: String,
        val file: File,
    )

    private enum class HistoryGroupType {
        TODAY,
        YESTERDAY,
        EARLIER,
    }

    companion object {
        const val EXTRA_URL = "history_url"
        const val EXTRA_OPEN_IN_NEW_TAB = "history_open_in_new_tab"
        private const val STATE_SELECTION_MODE = "state_selection_mode"
        private const val STATE_SELECTED_IDS = "state_selected_ids"
        private const val STATE_COLLAPSED_GROUPS = "state_collapsed_groups"

        fun createIntent(context: Context): Intent =
            Intent(context, HistoryActivity::class.java)

        fun readUrl(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }

        fun shouldOpenInNewTab(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_OPEN_IN_NEW_TAB, false) == true
    }
}
