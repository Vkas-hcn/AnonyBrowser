package com.anony.bro.wser.view.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.R
import com.anony.bro.wser.base.BaseActivity
import com.anony.bro.wser.data.history.HistoryRepository
import com.anony.bro.wser.data.settings.BrowserSettingsStore
import com.anony.bro.wser.data.settings.BrowserSettingsStore.AddressBarPosition
import com.anony.bro.wser.data.tab.TabSessionRepository
import com.anony.bro.wser.databinding.ActivitySettingsBinding
import com.anony.bro.wser.databinding.BottomSheetHistoryDeleteBinding
import com.anony.bro.wser.view.main.MainActivity
import java.io.File

class SettingsActivity : BaseActivity<ActivitySettingsBinding, SettingsViewModel>() {

    override val viewModel: SettingsViewModel by viewModels()

    override val applySystemBarPadding: Boolean = false

    override fun inflateBinding(inflater: LayoutInflater): ActivitySettingsBinding =
        ActivitySettingsBinding.inflate(inflater)

    override fun initViews(savedInstanceState: Bundle?) {
        setupSystemBars()
        binding.btnBack.setOnClickListener { finish() }
        binding.tvVersion.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)

        binding.rowAddressBottom.setOnClickListener {
            selectAddressBarPosition(AddressBarPosition.BOTTOM)
        }
        binding.rowAddressCenter.setOnClickListener {
            selectAddressBarPosition(AddressBarPosition.CENTER)
        }
        binding.rowAddressTop.setOnClickListener {
            selectAddressBarPosition(AddressBarPosition.TOP)
        }
        binding.tvCheckUpdates.setOnClickListener { openPlayStore() }
        binding.rowPrivacyPolicy.setOnClickListener { openPrivacyPolicy() }
        binding.rowLanguage.setOnClickListener {
            startActivity(LanguageActivity.createIntent(this))
        }
        binding.rowResetDefault.setOnClickListener { confirmResetDefaultSettings() }
        binding.rowResetBrowser.setOnClickListener { confirmResetBrowser() }

        renderAddressBarSelection(BrowserSettingsStore.getAddressBarPosition(this))
    }

    override fun observeData() = Unit

    private fun setupSystemBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = systemBars.top)
            binding.topBar.layoutParams = binding.topBar.layoutParams.apply {
                height = dp(56) + systemBars.top
            }
            binding.root.updatePadding(
                left = systemBars.left,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun selectAddressBarPosition(position: AddressBarPosition) {
        BrowserSettingsStore.setAddressBarPosition(this, position)
        renderAddressBarSelection(position)
    }

    private fun renderAddressBarSelection(position: AddressBarPosition) {
        binding.imgAddressBottom.setImageResource(
            if (position == AddressBarPosition.BOTTOM) R.drawable.ic_set_check else R.drawable.ic_dis_check,
        )
        binding.imgAddressCenter.setImageResource(
            if (position == AddressBarPosition.CENTER) R.drawable.ic_set_check else R.drawable.ic_dis_check,
        )
        binding.imgAddressTop.setImageResource(
            if (position == AddressBarPosition.TOP) R.drawable.ic_set_check else R.drawable.ic_dis_check,
        )
    }

    private fun openPlayStore() {
        val packageName = packageName
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(webIntent) }
                .onFailure { showToast(getString(R.string.settings_play_store_unavailable)) }
        }
    }

    private fun openPrivacyPolicy() {
        val url = getString(R.string.settings_privacy_policy_url).trim()
        if (url.isBlank()) {
            showToast(getString(R.string.settings_privacy_policy_unavailable))
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            showToast(getString(R.string.settings_privacy_policy_unavailable))
        }
    }

    private fun confirmResetDefaultSettings() {
        showConfirmSheet(
            message = getString(R.string.settings_reset_default_confirm),
            confirmText = getString(R.string.settings_reset_confirm),
        ) {
            BrowserSettingsStore.resetAppearanceToDefault(this)
            renderAddressBarSelection(AddressBarPosition.CENTER)
        }
    }

    private fun confirmResetBrowser() {
        showConfirmSheet(
            message = getString(R.string.settings_reset_browser_confirm),
            confirmText = getString(R.string.settings_reset_confirm),
        ) {
            performFullBrowserReset()
        }
    }

    private fun performFullBrowserReset() {
        HistoryRepository.get(this).clearAllBlocking()
        TabSessionRepository.get(this).clearBlocking()
        BrowserSettingsStore.resetUserShortcuts(this)
        runCatching { File(cacheDir, "tab_favicons").deleteRecursively() }
        startActivity(MainActivity.createBrowserResetIntent(this))
        finish()
    }

    private fun showConfirmSheet(
        message: String,
        confirmText: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetHistoryDeleteBinding.inflate(layoutInflater)
        sheetBinding.tvDeleteMessage.text = message
        sheetBinding.btnConfirmDelete.text = confirmText
        dialog.setContentView(sheetBinding.root)
        dialog.setCancelable(true)
        dialog.behavior.isDraggable = true
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        sheetBinding.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        sheetBinding.btnCancelDelete.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}
