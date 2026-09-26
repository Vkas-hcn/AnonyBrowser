package com.anony.bro.wser.hellohello

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.NetTool
import com.anony.bro.wser.vpn.VpnManager
import com.anony.bro.wser.vpn.VpnState
import org.json.JSONObject
import java.util.Locale

object NoticeConfigStore {
    private const val PREFS_NAME = "notice_config"
    private const val KEY_POP_CONFIG = "copop_config"
    private const val KEY_POP_CONFIG_SAMSUNG = "samsung_co_pop"
    private const val KEY_VPN_REMINDER = "vpn"
    private const val KEY_HOME_INT = "home_int"
    private const val KEY_BROWSER_INT = "browser_int"
    private const val KEY_BANNER_NOTIFY = "banner_notify"

    private const val DEFAULT_POP_CONFIG = """
        {"nopop_start":23,"nopop_end":6,"co_on":1,"co_t":1,"co_t_limit":240,"co_u":0,"co_u_limit":140,"co_a":10,"co_a_limit":140}
    """

    private const val DEFAULT_SAMSUNG_POP_CONFIG = """
        {"nopop_start":23,"nopop_end":6,"co_on":1,"co_t":1,"co_t_limit":240,"co_u":0,"co_u_limit":140,"co_a":10,"co_a_limit":140}
    """
    private const val DEFAULT_VPN_REMINDER = """{"co_on":1,"co_model":0,"co_time":5,"vpn_news_time":1}"""

    private fun appContext(): Context = GateBrowserApplication.get()

    fun init(context: Context) {}

    fun cacheNotice(raw: String) {
        val notice = runCatching {
            val root = JSONObject(raw)
            (root.optJSONObject("data") ?: root).optJSONObject("notice")
        }.getOrNull() ?: return

        appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            notice.optJSONObject(KEY_POP_CONFIG)?.let {
                putString(KEY_POP_CONFIG, it.toString())
            }
            notice.optJSONObject(KEY_POP_CONFIG_SAMSUNG)?.let {
                putString(KEY_POP_CONFIG_SAMSUNG, it.toString())
            }
            notice.optJSONObject(KEY_VPN_REMINDER)?.let {
                putString(KEY_VPN_REMINDER, it.toString())
            }
            listOf(KEY_HOME_INT, KEY_BROWSER_INT, KEY_BANNER_NOTIFY).forEach { key ->
                notice.optJSONObject(key)?.let { putString(key, it.toString()) }
            }
        }
        HintUtil.markLing()
    }

    fun getReceiveConfig(): UserPop {
        val key = if (isSamsung()) KEY_POP_CONFIG_SAMSUNG else KEY_POP_CONFIG
        val fallback = if (isSamsung()) DEFAULT_SAMSUNG_POP_CONFIG else DEFAULT_POP_CONFIG
        val json = appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, fallback)
            ?: fallback
        return parsePopConfig(json, fallback)
    }

    fun getVpnReminderConfig(): VpnReminderConfig {
        val json = appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VPN_REMINDER, DEFAULT_VPN_REMINDER)
            ?: DEFAULT_VPN_REMINDER
        return runCatching {
            JSONObject(json).toVpnReminderConfig()
        }.getOrElse {
            JSONObject(DEFAULT_VPN_REMINDER).toVpnReminderConfig()
        }
    }

    fun homeInterstitialConfig(): HomeInterstitialConfig = read(KEY_HOME_INT) { json ->
        HomeInterstitialConfig(json.optInt("int_on") == 1, json.optInt("int_num", 1).coerceAtLeast(1))
    } ?: HomeInterstitialConfig(false, 1)

    fun browserInterstitialEnabled(): Boolean = read(KEY_BROWSER_INT) { it.optInt("browser_on") == 1 } ?: false

    fun bannerNotifyConfig(): BannerNotifyConfig = read(KEY_BANNER_NOTIFY) { json ->
        BannerNotifyConfig(
            enabled = json.optInt("banner_on") == 1,
            durationSeconds = json.optInt("banner_time", 0).coerceAtLeast(0),
            ringEnabled = parseBannerRingEnabled(json.opt("ring_on")),
        )
    } ?: BannerNotifyConfig(false, 0)

    private fun <T> read(key: String, mapper: (JSONObject) -> T): T? = runCatching {
        val value = appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null) ?: return null
        mapper(JSONObject(value))
    }.getOrNull()

    internal fun parseBannerRingEnabled(value: Any?): Boolean =
        value is Number && value.toDouble() == 1.0

    fun getLong(key: String, defaultValue: Long = 0L): Long =
        appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(key, defaultValue)

    fun putLong(key: String, value: Long) {
        appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(key, value)
        }
    }

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    suspend fun isKoreanCountry(context: Context = appContext()): Boolean =
        isKoreanCountryCode(resolveCountryCode(context))

    internal fun isKoreanCountryCode(countryCode: String?): Boolean =
        countryCode?.trim()?.equals("KR", ignoreCase = true) == true

    internal fun resolveCountryCodeForPolicy(
        cachedCountryCode: String?,
        phoneCountryCode: String?,
        vpnConnected: Boolean,
    ): String? {
        val cached = cachedCountryCode.normalizedCountryCode()
        if (cached != null) return cached
        if (vpnConnected) return phoneCountryCode.normalizedCountryCode()
        return null
    }

    private suspend fun resolveCountryCode(context: Context): String? {
        val appContext = context.applicationContext
        val phoneCountryCode = phoneCountryCode()
        val cachedOrPhone = resolveCountryCodeForPolicy(
            cachedCountryCode = NetTool.getCachedCountryCode(appContext),
            phoneCountryCode = phoneCountryCode,
            vpnConnected = VpnManager.state != VpnState.DISCONNECTED,
        )
        if (cachedOrPhone != null) return cachedOrPhone
        return NetTool.fetchLocation(appContext)?.countryCode.normalizedCountryCode() ?: phoneCountryCode
    }

    private fun phoneCountryCode(): String? =
        Locale.getDefault().country.normalizedCountryCode()

    private fun String?.normalizedCountryCode(): String? =
        this?.trim()?.uppercase(Locale.US)?.takeIf { it.matches(Regex("[A-Z]{2}")) }

    private fun parsePopConfig(json: String, fallback: String): UserPop = runCatching {
        JSONObject(json).toUserPop()
    }.getOrElse {
        JSONObject(fallback).toUserPop()
    }

    private fun JSONObject.toUserPop(): UserPop = toUserPop(
        enabled = optInt("co_on"),
        intervalMinutes = optInt("co_t"),
        intervalLimit = optInt("co_t_limit"),
        unlockMinutes = optInt("co_u"),
        unlockLimit = optInt("co_u_limit"),
        alarmMinutes = optInt("co_a"),
        alarmLimit = optInt("co_a_limit"),
        quietStartHour = optInt("nopop_start"),
        quietEndHour = optInt("nopop_end"),
    )

    internal fun toUserPop(
        enabled: Int,
        intervalMinutes: Int,
        intervalLimit: Int,
        unlockMinutes: Int,
        unlockLimit: Int,
        alarmMinutes: Int,
        alarmLimit: Int,
        quietStartHour: Int,
        quietEndHour: Int,
    ): UserPop = UserPop(
        coIsSwitch = enabled == 1,
        cotTime = intervalMinutes,
        cotCount = intervalLimit,
        couTime = unlockMinutes,
        couCount = unlockLimit,
        coaTime = alarmMinutes,
        coaCount = alarmLimit,
        freeTimeOne = quietStartHour,
        freeTimeTwo = quietEndHour,
    )

    internal fun toVpnReminderConfig(
        enabled: Int,
        mode: Int,
        delayMinutes: Int,
        newsRefreshMinutes: Int = 1,
    ): VpnReminderConfig = VpnReminderConfig(
        enabled = enabled == 1,
        mode = if (mode == 1) 1 else 0,
        delayMinutes = delayMinutes.coerceAtLeast(0),
        newsRefreshMinutes = newsRefreshMinutes.takeIf { it > 0 } ?: 1,
    )

    private fun JSONObject.toVpnReminderConfig(): VpnReminderConfig = toVpnReminderConfig(
        enabled = optInt("co_on"),
        mode = optInt("co_model"),
        delayMinutes = optInt("co_time"),
        newsRefreshMinutes = optInt("vpn_news_time", 1),
    )
}

data class HomeInterstitialConfig(val enabled: Boolean, val interval: Int)
data class BannerNotifyConfig(
    val enabled: Boolean,
    val durationSeconds: Int,
    val ringEnabled: Boolean = false,
)
