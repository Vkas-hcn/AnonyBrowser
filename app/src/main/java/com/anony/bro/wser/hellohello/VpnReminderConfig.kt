package com.anony.bro.wser.hellohello

data class VpnReminderConfig(
    val enabled: Boolean,
    val mode: Int,
    val delayMinutes: Int,
    val newsRefreshMinutes: Int,
)

internal fun canShowVpnReminder(
    config: VpnReminderConfig,
    appForeground: Boolean,
    notificationsAllowed: Boolean,
): Boolean =
    config.enabled &&
        config.delayMinutes > 0 &&
        !appForeground &&
        notificationsAllowed
