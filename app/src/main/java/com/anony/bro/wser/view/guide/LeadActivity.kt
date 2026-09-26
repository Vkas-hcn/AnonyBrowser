package com.anony.bro.wser.view.guide

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.anony.bro.wser.R
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.base.BaseActivity
import com.anony.bro.wser.base.BaseViewModel
import com.anony.bro.wser.data.guide.LeadStore
import com.anony.bro.wser.data.settings.AppLanguageStore
import com.anony.bro.wser.databinding.ActivityLeadBinding
import com.anony.bro.wser.databinding.ItemLeadPageFeatureBinding
import com.anony.bro.wser.databinding.ItemLeadPageLanguageBinding
import com.anony.bro.wser.hellohello.HintUtil
import com.anony.bro.wser.view.main.MainActivity
import com.anony.bro.wser.view.vpn.VpnActivity
import com.anony.bro.wser.hellohello.VpnReminderNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LeadViewModel : BaseViewModel()

class LeadActivity : BaseActivity<ActivityLeadBinding, LeadViewModel>() {

    override val viewModel: LeadViewModel by viewModels()
    override val applySystemBarPadding: Boolean = false
    override val edgeToEdgeEnabled: Boolean = false

    private var openAfterGuideFromVpnReminder = false
    /** 已为哪个引导页刷新过广告；-1 表示尚未完成首次绑定（忽略 ViewPager 初始化回调）。 */
    private var nativeAdPage = -1
    private val indicators by lazy {
        listOf(binding.indicator0, binding.indicator1, binding.indicator2)
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityLeadBinding =
        ActivityLeadBinding.inflate(inflater)

    override fun initViews(savedInstanceState: Bundle?) {
        setupSystemBars()
        openAfterGuideFromVpnReminder =
            intent.getBooleanExtra(VpnReminderNotifier.EXTRA_OPENED_FROM_VPN_REMINDER, false)

        val startPage = savedInstanceState?.getInt(KEY_CURRENT_PAGE) ?: 0
        binding.leadPager.adapter = LeadPagerAdapter()
        binding.leadPager.offscreenPageLimit = PAGE_COUNT
        binding.leadPager.isUserInputEnabled = false
        binding.leadPager.setCurrentItem(startPage, false)
        updatePageUi(startPage)

        binding.leadPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageUi(position)
                // ViewPager2 初始化会异步回调 page 0；仅在真正换页时强制刷新，避免冲掉首屏广告。
                if (nativeAdPage >= 0 && position != nativeAdPage) {
                    nativeAdPage = position
                    refreshNativeAd(forceRefresh = true)
                }
            }
        })

        binding.btnLeadAction.setOnClickListener {
            val current = binding.leadPager.currentItem
            if (current < PAGE_COUNT - 1) {
                binding.leadPager.setCurrentItem(current + 1, true)
            } else {
                navigateToMain()
            }
        }

        observeNativeAd()
    }

    override fun observeData() = Unit

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_PAGE, binding.leadPager.currentItem)
    }

    override fun onDestroy() {
        AdMobManager.releaseNativeAdContainer(
            binding.nativeAd,
            AdMobManager.NativePlacement.HOME,
        )
        super.onDestroy()
    }

    private fun updatePageUi(position: Int) {
        binding.btnLeadAction.setText(
            if (position == PAGE_COUNT - 1) R.string.lead_continue else R.string.lead_next,
        )
        updatePageIndicators(position)
    }

    private fun updatePageIndicators(activeIndex: Int) {
        indicators.forEachIndexed { index, view ->
            val active = index == activeIndex
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = if (active) dp(24) else dp(8)
                height = dp(8)
            }
            view.setBackgroundResource(
                if (active) {
                    R.drawable.bg_lead_page_indicator_active
                } else {
                    R.drawable.bg_lead_page_indicator_inactive
                },
            )
        }
    }

    private fun observeNativeAd() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val loading = AdMobManager.isNativeAdLoading(AdMobManager.NativePlacement.HOME)
                    val state = AdMobManager.updateNativeAdContainer(
                        binding.nativeAd,
                        AdMobManager.NativePlacement.HOME,
                        loadWhenMissing = true,
                        preloadAfterDisplay = true,
                        forceRefresh = false,
                    )
                    if (state == AdMobManager.NativeContainerState.DISPLAYED && nativeAdPage < 0) {
                        nativeAdPage = binding.leadPager.currentItem
                    }
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

    private fun refreshNativeAd(forceRefresh: Boolean) {
        AdMobManager.updateNativeAdContainer(
            binding.nativeAd,
            AdMobManager.NativePlacement.HOME,
            loadWhenMissing = true,
            preloadAfterDisplay = true,
            forceRefresh = forceRefresh,
        )
    }

    private fun bindLanguagePage(binding: ItemLeadPageLanguageBinding) {
        val selectedTag = AppLanguageStore.selectedTag(this)
        val rows = listOf(
            "en" to (binding.rowEnglish to binding.imgEnglish),
            "ja" to (binding.rowJapanese to binding.imgJapanese),
            "ko" to (binding.rowKorean to binding.imgKorean),
            "es" to (binding.rowSpanish to binding.imgSpanish),
            "pt" to (binding.rowPortuguese to binding.imgPortuguese),
            "zh-TW" to (binding.rowTraditionalChinese to binding.imgTraditionalChinese),
            "ar" to (binding.rowArabic to binding.imgArabic),
        )
        rows.forEach { (tag, views) ->
            val (row, checkView) = views
            val selected = selectedTag == tag
            applyLeadLanguageRowStyle(row, checkView, selected)
            row.setOnClickListener {
                if (selectedTag != tag) {
                    AppLanguageStore.set(this@LeadActivity, tag)
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(tag),
                    )
                }
            }
        }
        binding.btnLanguageOk.setOnClickListener {
            rows.forEach { (tag, views) ->
                applyLeadLanguageRowStyle(
                    views.first,
                    views.second,
                    AppLanguageStore.selectedTag(this) == tag,
                )
            }
        }
    }

    private fun applyLeadLanguageRowStyle(
        row: LinearLayout,
        checkView: ImageView,
        selected: Boolean,
    ) {
        if (selected) {
            row.setBackgroundResource(R.drawable.bg_lead_language_item_selected)
            row.elevation = dp(2).toFloat()
            checkView.visibility = View.VISIBLE
        } else {
            row.setBackgroundResource(0)
            row.elevation = 0f
            checkView.visibility = View.GONE
        }
    }

    private fun navigateToMain() {
        LeadStore.markCompleted(this)
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.data?.let {
                action = Intent.ACTION_VIEW
                data = it
            }
            if (intent.hasExtra(HintUtil.CO_SHOW_TYPE)) {
                putExtra(HintUtil.CO_SHOW_TYPE, intent.getIntExtra(HintUtil.CO_SHOW_TYPE, -1))
                putExtra(
                    HintUtil.CO_NOTIFICATION_ID,
                    intent.getIntExtra(HintUtil.CO_NOTIFICATION_ID, -1),
                )
            }
        }
        startActivity(mainIntent)
        if (openAfterGuideFromVpnReminder) {
            startActivity(Intent(this, VpnActivity::class.java))
        }
        finish()
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
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.leadRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.leadPager.updatePadding(top = systemBars.top)
            binding.leadRoot.updatePadding(
                left = systemBars.left,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.leadRoot)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class LeadPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = PAGE_COUNT

        override fun getItemViewType(position: Int): Int =
            if (position == PAGE_LANGUAGE) VIEW_TYPE_LANGUAGE else VIEW_TYPE_FEATURE

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_LANGUAGE) {
                LanguagePageViewHolder(
                    ItemLeadPageLanguageBinding.inflate(inflater, parent, false),
                )
            } else {
                FeaturePageViewHolder(
                    ItemLeadPageFeatureBinding.inflate(inflater, parent, false),
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is FeaturePageViewHolder -> holder.bind(position)
                is LanguagePageViewHolder -> bindLanguagePage(holder.binding)
            }
        }
    }

    private inner class FeaturePageViewHolder(
        private val itemBinding: ItemLeadPageFeatureBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(position: Int) {
            when (position) {
                PAGE_VPN -> {
                    itemBinding.imgIllustration.setImageResource(R.drawable.ic_lead_vpn)
                    itemBinding.tvTitle.setText(R.string.lead_vpn_title)
                    itemBinding.tvSubtitle.setText(R.string.lead_vpn_subtitle)
                }

                PAGE_SEARCH -> {
                    itemBinding.imgIllustration.setImageResource(R.drawable.ic_lead_search)
                    itemBinding.tvTitle.setText(R.string.lead_search_title)
                    itemBinding.tvSubtitle.setText(R.string.lead_search_subtitle)
                }
            }
        }
    }

    private class LanguagePageViewHolder(
        val binding: ItemLeadPageLanguageBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val PAGE_COUNT = 3
        private const val PAGE_VPN = 0
        private const val PAGE_SEARCH = 1
        private const val PAGE_LANGUAGE = 2
        private const val VIEW_TYPE_FEATURE = 0
        private const val VIEW_TYPE_LANGUAGE = 1
        private const val KEY_CURRENT_PAGE = "lead_current_page"
        private const val NATIVE_AD_POLL_INTERVAL_MS = 300L
        private const val NATIVE_AD_IDLE_POLL_INTERVAL_MS = 2_000L

        fun createIntent(
            context: Context,
            openUri: Uri? = null,
            newsShowType: Int? = null,
            newsNotificationId: Int? = null,
            openFromVpnReminder: Boolean = false,
        ): Intent =
            Intent(context, LeadActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                openUri?.let {
                    action = Intent.ACTION_VIEW
                    data = it
                }
                newsShowType?.let {
                    putExtra(HintUtil.CO_SHOW_TYPE, it)
                    putExtra(HintUtil.CO_NOTIFICATION_ID, newsNotificationId ?: -1)
                }
                if (openFromVpnReminder) {
                    putExtra(VpnReminderNotifier.EXTRA_OPENED_FROM_VPN_REMINDER, true)
                }
            }
    }
}
