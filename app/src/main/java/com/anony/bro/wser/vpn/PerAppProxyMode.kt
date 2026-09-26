package com.anony.bro.wser.vpn

/**
 * 分应用代理模式
 */
enum class PerAppProxyMode {
    /**
     * 禁用分应用代理（全局代理）
     */
    DISABLED,

    /**
     * 白名单模式：只有列表中的应用走代理
     */
    INCLUDE,

    /**
     * 黑名单模式：列表中的应用不走代理（直连），其他应用走代理
     */
    EXCLUDE
}
