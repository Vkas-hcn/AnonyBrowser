package com.anony.bro.wser.view.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatDelegate
import com.anony.bro.wser.R
import com.anony.bro.wser.base.BaseActivity
import com.anony.bro.wser.base.BaseViewModel
import com.anony.bro.wser.data.settings.AppLanguageStore
import com.anony.bro.wser.databinding.ActivityLanguageBinding

class LanguageViewModel : BaseViewModel()

class LanguageActivity : BaseActivity<ActivityLanguageBinding, LanguageViewModel>() {

    override val viewModel: LanguageViewModel by viewModels()
    override val applySystemBarPadding: Boolean = false

    override fun inflateBinding(inflater: LayoutInflater): ActivityLanguageBinding =
        ActivityLanguageBinding.inflate(inflater)

    override fun initViews(savedInstanceState: Bundle?) {
        setupSystemBars()
        binding.btnBack.setOnClickListener { finish() }
        renderLanguages()
    }

    override fun observeData() = Unit

    private fun renderLanguages() {
        val selectedTag = AppLanguageStore.selectedTag(this)
        val rows = listOf(
            "en" to (binding.rowEnglish to binding.imgEnglish),
            "zh-TW" to (binding.rowTraditionalChinese to binding.imgTraditionalChinese),
            "ja" to (binding.rowJapanese to binding.imgJapanese),
            "ko" to (binding.rowKorean to binding.imgKorean),
            "es" to (binding.rowSpanish to binding.imgSpanish),
            "pt" to (binding.rowPortuguese to binding.imgPortuguese),
            "ar" to (binding.rowArabic to binding.imgArabic),
        )
        rows.forEach { (tag, views) ->
            views.first.setOnClickListener {
                if (selectedTag != tag) {
                    AppLanguageStore.set(this, tag)
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(tag),
                    )
                }
            }
            views.second.setImageResource(
                if (selectedTag == tag) R.drawable.ic_check else R.drawable.ic_dis_check,
            )
        }
    }

    private fun setupSystemBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, LanguageActivity::class.java)
    }
}
