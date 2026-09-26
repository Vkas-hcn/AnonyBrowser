package com.anony.bro.wser.guide

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.R

internal enum class BrowserGuidePath {
    ONBOARDING,
    FIRST_SEARCH_RESULT,
}

object BrowserGuideControl {

    internal const val PREFS_NAME = "browser_guide_control_prefs"
    internal const val KEY_DEFAULT_BROWSER_CONFIGURED = "browser_guide_default_configured"
    private const val KEY_BROWSER_GUIDE_CONTROL = "browser_guide_control"

    private val remoteConfig by lazy { FirebaseRemoteConfig.getInstance() }

    fun init() {
        runCatching {
            if (BuildConfig.DEBUG) {
                remoteConfig.setConfigSettingsAsync(
                    FirebaseRemoteConfigSettings.Builder()
                        .setMinimumFetchIntervalInSeconds(0)
                        .build(),
                )
            } else {
                remoteConfig.setConfigSettingsAsync(
                    FirebaseRemoteConfigSettings.Builder()
                        .setMinimumFetchIntervalInSeconds(3600)
                        .build(),
                )
            }
            remoteConfig.setDefaultsAsync(R.xml.browser_guide_remote_config_defaults)
            remoteConfig.fetchAndActivate()
        }
    }

    fun shouldShowDuringOnboarding(context: Context): Boolean =
        shouldShowDuringOnboarding(
            path = path(),
            browserConfigured = hasDefaultBrowserConfigured(context),
            isDefaultBrowser = isDefaultBrowser(context),
        )

    fun shouldShowAfterFirstSearch(context: Context): Boolean =
        shouldShowAfterFirstSearch(
            path = path(),
            browserConfigured = hasDefaultBrowserConfigured(context),
            isDefaultBrowser = isDefaultBrowser(context),
        )

    fun markDefaultBrowserConfigured(context: Context) {
        preferences(context).edit { putBoolean(KEY_DEFAULT_BROWSER_CONFIGURED, true) }
    }

    fun hasDefaultBrowserConfigured(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DEFAULT_BROWSER_CONFIGURED, false)

    fun isDefaultBrowser(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved =
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    internal fun pathFor(enabled: Boolean): BrowserGuidePath =
        if (enabled) BrowserGuidePath.ONBOARDING else BrowserGuidePath.FIRST_SEARCH_RESULT

    internal fun shouldShowDuringOnboarding(
        path: BrowserGuidePath,
        browserConfigured: Boolean,
        isDefaultBrowser: Boolean,
    ): Boolean =
        path == BrowserGuidePath.ONBOARDING &&
                !browserConfigured &&
                !isDefaultBrowser

    internal fun shouldShowAfterFirstSearch(
        path: BrowserGuidePath,
        browserConfigured: Boolean,
        isDefaultBrowser: Boolean,
    ): Boolean =
        path == BrowserGuidePath.FIRST_SEARCH_RESULT &&
                !browserConfigured &&
                !isDefaultBrowser

    private fun path(): BrowserGuidePath =
        pathFor(
            runCatching { remoteConfig.getBoolean(KEY_BROWSER_GUIDE_CONTROL) }.getOrDefault(
                false
            )
        )

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
