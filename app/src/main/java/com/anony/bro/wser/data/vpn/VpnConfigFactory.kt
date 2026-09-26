package com.anony.bro.wser.data.vpn

import org.json.JSONArray
import org.json.JSONObject

object VpnConfigFactory {
    fun create(server: VpnServer, catalog: VpnServerCatalog): String {
        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("level", "info")
                put("timestamp", true)
            })
            put("dns", JSONObject().apply {
                put("servers", JSONArray().put(
                    JSONObject().apply {
                        put("tag", "bootstrap-dns")
                        put("address", catalog.fallbackDns.ifBlank { "1.1.1.1" })
                        put("detour", "direct")
                    }
                ))
                put("final", "bootstrap-dns")
                put("strategy", "ipv4_only")
            })
            put("inbounds", JSONArray().put(
                JSONObject().apply {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("interface_name", "tun0")
                    put("address", JSONArray().put("172.19.0.1/30"))
                    put("mtu", 1400)
                    put("stack", "gvisor")
                    put("auto_route", true)
                    put("strict_route", true)
                }
            ))
            put("outbounds", JSONArray().put(
                JSONObject().apply {
                    put("type", "socks")
                    put("tag", "proxy")
                    put("server", server.hostAddress)
                    put("server_port", server.portNumber)
                    put("version", "5")
                    put("username", server.username)
                    put("password", server.password)
                }
            ).put(
                JSONObject().apply {
                    put("type", "direct")
                    put("tag", "direct")
                }
            ))
            put("route", JSONObject().apply {
                put("auto_detect_interface", true)
                put("override_android_vpn", true)
                put("rules", JSONArray().put(
                    JSONObject().apply {
                        put("inbound", "tun-in")
                        put("action", "sniff")
                    }
                ).put(
                    JSONObject().apply {
                        put("port", 53)
                        put("action", "hijack-dns")
                    }
                ).put(
                    JSONObject().apply {
                        put("port", 853)
                        put("action", "hijack-dns")
                    }
                ).put(
                    JSONObject().apply {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    }
                ).put(
                    JSONObject().apply {
                        put("domain", JSONArray().put(server.hostAddress))
                        put("outbound", "direct")
                    }
                ))
                put("final", "proxy")
            })
        }.toString(2)
    }

    var admobConfig = """
{
    "ad_switch_control": {
        "enable_inters_connect": true,
        "enable_open_back": true,
        "enable_home_native": true,
        "enable_connect_native": true,
        "enable_inters_guide_1": true,
        "enable_inters_guide_2": true,
        "enable_inters_home": true,
        "enable_inters_back": true,
        "enable_search_inters_back": true,
        "enable_bar_banner": true
    },
    "ad_unit_ids": {
        "inters_connect": "ca-app-pub-3940256099942544/1033173712",
        "open_back": "ca-app-pub-3940256099942544/9257395921",
        "home_native": "ca-app-pub-3940256099942544/2247696110",
        "connect_native": "ca-app-pub-3940256099942544/2247696110",
        "inters_guide_1": "ca-app-pub-3940256099942544/1033173712",
        "inters_guide_2": "ca-app-pub-3940256099942544/1033173712",
        "inters_home": "ca-app-pub-3940256099942544/1033173712",
        "inters_back": "ca-app-pub-3940256099942544/1033173712",
        "search_inters_back": "ca-app-pub-3940256099942544/1033173712",
        "bar_banner": "ca-app-pub-3940256099942544/9214589741"
    },
    "ad_frequency_control": {
        "max_native_display_count": 10,
        "max_interstitial_display_count": 10,
        "max_open_display_count": 10,
        "inters_connect_loadtime": 10000,
        "inters_open_loadtime": 10000,
        "max_banner_display_count": 10
    },
    "ad_debug_settings": {
        "is_debug_mode": false
    }
}
    """.trimIndent()
}
