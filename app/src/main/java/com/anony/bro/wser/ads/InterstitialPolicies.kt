package com.anony.bro.wser.ads

/**
 * Process-scoped home interstitial interval.
 * After a successful show, the next [interval] home triggers skip; Activity/Fragment
 * recreation does not reset this while the process is alive.
 */
object HomeInterstitialPolicy {
    private var skippedTriggers = 0
    private var attemptInFlight = false

    @Synchronized
    fun canAttempt(enabled: Boolean): Boolean {
        if (!enabled) return false
        if (attemptInFlight) return false
        if (skippedTriggers > 0) {
            skippedTriggers--
            return false
        }
        attemptInFlight = true
        return true
    }

    @Synchronized
    fun onShown(interval: Int) {
        skippedTriggers = interval.coerceAtLeast(1)
        attemptInFlight = false
    }

    @Synchronized
    fun releaseAttempt() {
        attemptInFlight = false
    }
}
