package com.anony.bro.wser.view.guide

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.anony.bro.wser.R
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.settings.AppLanguageStore
import com.anony.bro.wser.databinding.ActivityDefaultBrowserGuideBinding
import com.anony.bro.wser.guide.BrowserGuideControl

class DefaultBrowserGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDefaultBrowserGuideBinding
    private var bottomButtonMarginPx = 0
    private val blocksBackNavigation: Boolean
        get() = intent.getBooleanExtra(EXTRA_BLOCKING, false)

    private val defaultBrowserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishAfterUserAction()
        }

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
        binding = ActivityDefaultBrowserGuideBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        bottomButtonMarginPx = binding.btnBrowserGuideLater.marginBottomCompat()
        setupSystemBars()
        binding.btnBrowserGuideAllow.setOnClickListener(::requestDefaultBrowser)
        binding.btnBrowserGuideLater.setOnClickListener { finishAfterUserAction() }
        if (blocksBackNavigation) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            })
        }
    }

    private fun requestDefaultBrowser(@Suppress("UNUSED_PARAMETER") view: android.view.View) {
        if (BrowserGuideControl.isDefaultBrowser(this)) {
            finishAfterUserAction()
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)
                ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_BROWSER) }
                ?.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
        } else {
            null
        } ?: Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        GateBrowserApplication.get().skipNextHotStart()
        runCatching { defaultBrowserLauncher.launch(intent) }
            .onFailure { showOpenSettingsError() }
    }

    private fun finishAfterUserAction() {
        val isDefault = BrowserGuideControl.isDefaultBrowser(this)
        if (isDefault) {
            BrowserGuideControl.markDefaultBrowserConfigured(this)
            UpDataTool.trackEvent("set_default_browser")
        }
        setResult(if (isDefault) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    private fun showOpenSettingsError() {
        android.widget.Toast.makeText(
            this,
            R.string.set_browser_open_settings_failed,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.browserGuide) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.btnBrowserGuideLater.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = bottomButtonMarginPx + systemBars.bottom
            }
            binding.browserGuide.updatePadding(left = systemBars.left, right = systemBars.right)
            insets
        }
        ViewCompat.requestApplyInsets(binding.browserGuide)
    }

    private fun android.view.View.marginBottomCompat(): Int =
        (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    companion object {
        private const val EXTRA_BLOCKING = "extra_default_browser_guide_blocking"

        fun createIntent(context: Context, blocking: Boolean): Intent =
            Intent(context, DefaultBrowserGuideActivity::class.java)
                .putExtra(EXTRA_BLOCKING, blocking)
    }
}
