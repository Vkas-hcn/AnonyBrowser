package com.anony.bro.wser.ads

import org.json.JSONObject

data class AdConfig(
    val enabled: Boolean = false,
    val adsDebugMode: Boolean = false,
    val nativeDailyLimit: Int = 0,
    val interstitialDailyLimit: Int = 0,
    val openDailyLimit: Int = 0,
    val bannerDailyLimit: Int = 0,
    val intersConnectLoadTime: Int = 10000,
    val intersOpenLoadTime: Int = 10000,
    val intersConnectConfig: AdTypeConfig? = null,
    val intersBackConfig: AdTypeConfig? = null,
    val intersGuide1Config: AdTypeConfig? = null,
    val intersGuide2Config: AdTypeConfig? = null,
    val intersHomeConfig: AdTypeConfig? = null,
    val intersBrowserBackConfig: AdTypeConfig? = null,
    val searchIntersBackConfig: AdTypeConfig? = null,
    val barBannerConfig: AdTypeConfig? = null,
    /** Kept for source compatibility; represents the new `connect_native` placement. */
    val nativeCommonConfig: AdTypeConfig? = null,
    val homeNativeConfig: AdTypeConfig? = null,
)

data class AdTypeConfig(
    val enabled: Boolean = false,
    val adUnits: List<AdUnit> = emptyList()
)

data class AdUnit(
    val id: String,
    val name: String
)

/**
 * 广告状态枚举
 */
enum class AdState {
    NOT_LOADED,
    LOADING,
    LOADED,
    SHOWING,
    FAILED,
    CLOSED
}

object AdConfigSerializer {

    fun toJson(config: AdConfig): String {
        val switchControl = JSONObject().apply {
            put("enable_inters_connect", config.intersConnectConfig?.enabled == true)
            put("enable_open_back", config.intersBackConfig?.enabled == true)
            put("enable_home_native", config.homeNativeConfig?.enabled == true)
            put("enable_connect_native", config.nativeCommonConfig?.enabled == true)
            put("enable_inters_guide_1", config.intersGuide1Config?.enabled == true)
            put("enable_inters_guide_2", config.intersGuide2Config?.enabled == true)
            put("enable_inters_home", config.intersHomeConfig?.enabled == true)
            put("enable_inters_back", config.intersBrowserBackConfig?.enabled == true)
            put("enable_search_inters_back", config.searchIntersBackConfig?.enabled == true)
            put("enable_bar_banner", config.barBannerConfig?.enabled == true)
        }
        val adUnitIds = JSONObject().apply {
            put("inters_connect", joinAdIds(config.intersConnectConfig))
            put("open_back", joinAdIds(config.intersBackConfig))
            put("home_native", joinAdIds(config.homeNativeConfig))
            put("connect_native", joinAdIds(config.nativeCommonConfig))
            put("inters_guide_1", joinAdIds(config.intersGuide1Config))
            put("inters_guide_2", joinAdIds(config.intersGuide2Config))
            put("inters_home", joinAdIds(config.intersHomeConfig))
            put("inters_back", joinAdIds(config.intersBrowserBackConfig))
            put("search_inters_back", joinAdIds(config.searchIntersBackConfig))
            put("bar_banner", joinAdIds(config.barBannerConfig))
        }
        val frequencyControl = JSONObject().apply {
            put("max_native_display_count", config.nativeDailyLimit)
            put("max_interstitial_display_count", config.interstitialDailyLimit)
            put("max_open_display_count", config.openDailyLimit)
            put("inters_connect_loadtime", config.intersConnectLoadTime)
            put("inters_open_loadtime", config.intersOpenLoadTime)
            put("max_banner_display_count", config.bannerDailyLimit)
        }
        val debugSettings = JSONObject().apply {
            put("is_debug_mode", config.adsDebugMode)
        }
        return JSONObject().apply {
            put("ad_switch_control", switchControl)
            put("ad_unit_ids", adUnitIds)
            put("ad_frequency_control", frequencyControl)
            put("ad_debug_settings", debugSettings)
        }.toString()
    }

    fun fromJson(json: String): AdConfig {
        val jsonObject = JSONObject(json)
        if (!jsonObject.has("ad_switch_control")) {
            return AdConfig()
        }

        val switchControl = jsonObject.optJSONObject("ad_switch_control") ?: JSONObject()
        val adUnitIds = jsonObject.optJSONObject("ad_unit_ids") ?: JSONObject()
        val frequencyControl = jsonObject.optJSONObject("ad_frequency_control") ?: JSONObject()
        val debugSettings = jsonObject.optJSONObject("ad_debug_settings") ?: JSONObject()

        val intersConnectEnabled = switchControl.optBoolean("enable_inters_connect", false)
        val intersBackEnabled = switchControl.optBoolean("enable_open_back", false)
        val homeNativeEnabled = switchControl.optBoolean("enable_home_native", false)
        val connectNativeEnabled = switchControl.optBoolean("enable_connect_native", false)
        val intersGuide1Enabled = switchControl.optBoolean("enable_inters_guide_1", false)
        val intersGuide2Enabled = switchControl.optBoolean("enable_inters_guide_2", false)
        val intersHomeEnabled = switchControl.optBoolean("enable_inters_home", false)
        val intersBrowserBackEnabled = switchControl.optBoolean("enable_inters_back", false)
        val searchIntersBackEnabled = switchControl.optBoolean("enable_search_inters_back", false)
        val barBannerEnabled = switchControl.optBoolean("enable_bar_banner", false)
        val anyEnabled =
            intersConnectEnabled ||
                intersBackEnabled ||
                homeNativeEnabled ||
                connectNativeEnabled ||
                intersGuide1Enabled || intersGuide2Enabled || intersHomeEnabled || intersBrowserBackEnabled ||
                searchIntersBackEnabled || barBannerEnabled
        val interstitialDailyLimit = frequencyControl
            .optInt("max_interstitial_display_count", 0)
            .coerceAtLeast(0)
        val openDailyLimit = if (frequencyControl.has("max_open_display_count")) {
            frequencyControl.optInt("max_open_display_count", 0).coerceAtLeast(0)
        } else {
            interstitialDailyLimit
        }

        return AdConfig(
            enabled = anyEnabled,
            adsDebugMode = debugSettings.optBoolean("is_debug_mode", false),
            nativeDailyLimit = frequencyControl.optInt("max_native_display_count", 0).coerceAtLeast(0),
            interstitialDailyLimit = interstitialDailyLimit,
            openDailyLimit = openDailyLimit,
            bannerDailyLimit = frequencyControl.optInt("max_banner_display_count", 0).coerceAtLeast(0),
            intersConnectLoadTime = frequencyControl.optInt("inters_connect_loadtime", 10000).coerceAtLeast(0),
            intersOpenLoadTime = frequencyControl.optInt("inters_open_loadtime", 10000).coerceAtLeast(0),
            intersConnectConfig = AdTypeConfig(
                enabled = intersConnectEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("inters_connect", ""), "inters_connect")
            ),
            intersBackConfig = AdTypeConfig(
                enabled = intersBackEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("open_back", ""), "open_back")
            ),
            nativeCommonConfig = AdTypeConfig(
                enabled = connectNativeEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("connect_native", ""), "connect_native")
            ),
            homeNativeConfig = AdTypeConfig(
                enabled = homeNativeEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("home_native", ""), "home_native")
            ),
            intersGuide1Config = AdTypeConfig(
                enabled = intersGuide1Enabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("inters_guide_1", ""), "inters_guide_1")
            ),
            intersGuide2Config = AdTypeConfig(
                enabled = intersGuide2Enabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("inters_guide_2", ""), "inters_guide_2")
            ),
            intersHomeConfig = AdTypeConfig(
                enabled = intersHomeEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("inters_home", ""), "inters_home")
            ),
            intersBrowserBackConfig = AdTypeConfig(
                enabled = intersBrowserBackEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("inters_back", ""), "inters_back")
            ),
            searchIntersBackConfig = AdTypeConfig(
                enabled = searchIntersBackEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("search_inters_back", ""), "search_inters_back")
            ),
            barBannerConfig = AdTypeConfig(
                enabled = barBannerEnabled,
                adUnits = parseCsvAdUnits(adUnitIds.optString("bar_banner", ""), "bar_banner")
            ),
        )
    }

    private fun joinAdIds(type: AdTypeConfig?): String {
        return type?.adUnits?.joinToString(",") { it.id }.orEmpty()
    }

    private fun parseCsvAdUnits(csv: String, prefix: String): List<AdUnit> {
        if (csv.isBlank()) return emptyList()
        return csv.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, id -> AdUnit(id = id, name = "${prefix}_${index + 1}") }
    }
}
