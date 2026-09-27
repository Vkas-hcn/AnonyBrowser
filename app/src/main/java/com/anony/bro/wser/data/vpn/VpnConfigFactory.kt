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
}
