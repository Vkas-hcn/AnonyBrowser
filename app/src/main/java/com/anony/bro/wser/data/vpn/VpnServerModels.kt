package com.anony.bro.wser.data.vpn

import androidx.annotation.DrawableRes
import com.anony.bro.wser.R
import java.io.Serializable
import java.util.Locale

const val DEFAULT_VPN_CONNECTION_DURATION_SECONDS = 300L

data class VpnServerCatalog(
    val cacheLifetimeSec: String,
    val failoverSwitch: Boolean,
    val fallbackDns: String,
    val pingSwitch: String,
    val fastIndex: Int,
    val fbid: String,
    val notice: VpnNoticeConfig?,
    /** JSON content of the dispatch response's `ad_config` object. */
    val adConfig: String,
    val endpointCluster: List<VpnServer>
)

fun VpnServerCatalog.connectionDurationSeconds(): Long =
    VpnServerCatalogParser.parseCacheLifetimeSeconds(cacheLifetimeSec)

data class VpnNoticeConfig(
    val copopConfig: CoPopConfig?,
    val samsungCoPop: CoPopConfig?,
    val vpnConfig: VpnReminderNoticeConfig?,
    val homeInterstitial: HomeInterstitialNoticeConfig?,
    val browserInterstitial: BrowserInterstitialNoticeConfig?,
    val bannerNotify: BannerNotifyNoticeConfig?,
)

data class HomeInterstitialNoticeConfig(val enabled: Int?, val interval: Int?)
data class BrowserInterstitialNoticeConfig(val enabled: Int?)
data class BannerNotifyNoticeConfig(val enabled: Int?, val intervalMinutes: Int?, val ringOn: Int? = null)

data class VpnReminderNoticeConfig(
    val enabled: Int?,
    val mode: Int?,
    val delayMinutes: Int?,
    val newsRefreshMinutes: Int?,
)

data class CoPopConfig(
    val noPopStart: Int?,
    val noPopEnd: Int?,
    val enabled: Int?,
    val intervalSeconds: Int?,
    val intervalLimit: Int?,
    val userThreshold: Int?,
    val userLimit: Int?,
    val actionThreshold: Int?,
    val actionLimit: Int?,
)

data class VpnServer(
    val locationLabel: String,
    val geoZone: String,
    val hostAddress: String,
    val portNumber: Int,
    val username: String,
    val password: String,
    val premiumFlag: Boolean
) : Serializable


@DrawableRes
fun VpnServer.flagIconRes(): Int {
    val normalizedLabel = locationLabel.lowercase(Locale.US)
    return when (geoZone.uppercase(Locale.US)) {
        "FR" -> R.mipmap.ic_france
        "US" -> R.mipmap.ic_united_states
        "DE" -> R.mipmap.ic_germany
        "CA" -> R.mipmap.ic_canada
        "AU" -> R.mipmap.ic_australia
        "CH" -> R.mipmap.ic_switzerland
        "SG" -> R.mipmap.ic_singapore
        "JP" -> R.mipmap.ic_japan
        "GB", "UK" -> R.mipmap.ic_united_kingom
        "BR" -> R.mipmap.ic_brazil
        "IN" -> R.mipmap.ic_india
        "IT" -> R.mipmap.ic_italy
        "KR" -> R.mipmap.ic_korea
        "NL" -> R.mipmap.ic_netherlands
        "SE" -> R.mipmap.ic_sweden
        "ES" -> R.mipmap.ic_spain
        else -> when {
            "france" in normalizedLabel -> R.mipmap.ic_france
            "united states" in normalizedLabel || "usa" in normalizedLabel -> R.mipmap.ic_united_states
            "germany" in normalizedLabel -> R.mipmap.ic_germany
            "canada" in normalizedLabel -> R.mipmap.ic_canada
            "australia" in normalizedLabel -> R.mipmap.ic_australia
            "switzerland" in normalizedLabel -> R.mipmap.ic_switzerland
            "singapore" in normalizedLabel -> R.mipmap.ic_singapore
            "japan" in normalizedLabel -> R.mipmap.ic_japan
            else -> R.mipmap.ic_launcher_round
        }
    }
}
//国家检测转国家名称
fun VpnServer.countryName(): String {
    return when (geoZone.uppercase(Locale.US)) {
        "FR" -> "France"
        "US" -> "United States"
        "DE" -> "Germany"
        "CA" -> "Canada"
        "AU" -> "Australia"
        "CH" -> "Switzerland"
        "SG" -> "Singapore"
        "JP" -> "Japan"
        "GB", "UK" -> "United Kingdom"
        "BR" -> "Brazil"
        "IN" -> "India"
        "IT" -> "Italy"
        "KR" -> "South Korea"
        "NL" -> "Netherlands"
        "SE" -> "Sweden"
        "ES" -> "Spain"
        else -> locationLabel
    }
}

