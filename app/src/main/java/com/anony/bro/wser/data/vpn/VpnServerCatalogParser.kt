package com.anony.bro.wser.data.vpn

import org.json.JSONObject

object VpnServerCatalogParser {
    fun parseCacheLifetimeSeconds(raw: String?): Long =
        raw
            ?.substringBefore(',')
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_VPN_CONNECTION_DURATION_SECONDS

    fun parse(raw: String): VpnServerCatalog {
        val root = JSONObject(raw)
        val data = root.optJSONObject("data") ?: root
        val endpoints = data.optJSONArray("endpoint_cluster")
            ?: error("Missing endpoint_cluster")
        val adConfig = data.optJSONObject("ad_config")?.toString().orEmpty()
        val notice = data.optJSONObject("notice")?.let { noticeObject ->
            VpnNoticeConfig(
                copopConfig = noticeObject.optJSONObject("copop_config")?.toCoPopConfig(),
                samsungCoPop = noticeObject.optJSONObject("samsung_co_pop")?.toCoPopConfig(),
                vpnConfig = noticeObject.optJSONObject("vpn")?.toVpnReminderConfig(),
                homeInterstitial = noticeObject.optJSONObject("home_int")?.let {
                    HomeInterstitialNoticeConfig(it.optIntOrNull("int_on"), it.optIntOrNull("int_num"))
                },
                browserInterstitial = noticeObject.optJSONObject("browser_int")?.let {
                    BrowserInterstitialNoticeConfig(it.optIntOrNull("browser_on"))
                },
                bannerNotify = noticeObject.optJSONObject("banner_notify")?.let {
                    BannerNotifyNoticeConfig(
                        it.optIntOrNull("banner_on"),
                        it.optIntOrNull("banner_time"),
                        it.optBinaryIntOrNull("ring_on"),
                    )
                },
            )
        }

        val servers = buildList {
            for (index in 0 until endpoints.length()) {
                val item = endpoints.optJSONObject(index) ?: continue
                val hostAddress = item.optString("host_address").trim()
                val portNumber = item.optIntOrNull("port_number")
                val username = item.optString("username").trim()
                val password = item.optString("password").trim()

                if (hostAddress.isBlank() || username.isBlank() || password.isBlank()) continue
                if (portNumber == null || portNumber !in 1..65535) continue

                add(
                    VpnServer(
                        locationLabel = item.optString("location_label").trim()
                            .ifBlank { hostAddress },
                        geoZone = item.optString("geo_zone").trim(),
                        hostAddress = hostAddress,
                        portNumber = portNumber,
                        username = username,
                        password = password,
                        premiumFlag = item.optBoolean("premium_flag", false),
                    ),
                )
            }
        }

        return VpnServerCatalog(
            cacheLifetimeSec = data.optString("cache_lifetime_sec"),
            failoverSwitch = data.optBoolean("failover_switch", false),
            fallbackDns = data.optString("fallback_dns", "8.8.8.8"),
            pingSwitch = data.optString("ping_switch"),
            fastIndex = data.optIntOrNull("fast_index") ?: -1,
            fbid = data.optString("fbid"),
            notice = notice,
            adConfig = adConfig,
            endpointCluster = servers,
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.toCoPopConfig(): CoPopConfig = CoPopConfig(
        noPopStart = optIntOrNull("nopop_start"),
        noPopEnd = optIntOrNull("nopop_end"),
        enabled = optIntOrNull("co_on"),
        intervalSeconds = optIntOrNull("co_t"),
        intervalLimit = optIntOrNull("co_t_limit"),
        userThreshold = optIntOrNull("co_u"),
        userLimit = optIntOrNull("co_u_limit"),
        actionThreshold = optIntOrNull("co_a"),
        actionLimit = optIntOrNull("co_a_limit"),
    )

    private fun JSONObject.toVpnReminderConfig(): VpnReminderNoticeConfig = VpnReminderNoticeConfig(
        enabled = optIntOrNull("co_on"),
        mode = optIntOrNull("co_model"),
        delayMinutes = optIntOrNull("co_time"),
        newsRefreshMinutes = parseVpnNewsRefreshMinutes(opt("vpn_news_time")),
    )

    internal fun parseVpnNewsRefreshMinutes(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun JSONObject.optBinaryIntOrNull(name: String): Int? = when (val value = opt(name)) {
        is Number -> value.toDouble().takeIf { it == 0.0 || it == 1.0 }?.toInt()
        else -> null
    }
}
