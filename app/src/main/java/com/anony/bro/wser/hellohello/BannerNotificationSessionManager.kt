package com.anony.bro.wser.hellohello

import android.os.SystemClock
import kotlin.math.ceil

data class ActiveBannerNotification(
    val sessionId: Long,
    val notificationId: Int,
    val newsId: String,
    val content: NotificationContent,
    val remainingTriggers: Int,
    val accumulatedStayMs: Long,
    val expiresAtElapsedMs: Long,
    val lastShownAtElapsedMs: Long,
    val hasPlayedAlert: Boolean,
)

data class BannerNotificationDispatch(
    val notification: ActiveBannerNotification,
    val isFirstInSession: Boolean,
)

class BannerNotificationSessionManager(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private var nextSessionId = 0L
    private var active: ActiveBannerNotification? = null

    @Synchronized
    fun tryStart(content: NotificationContent, durationSeconds: Int, notificationId: Int): ActiveBannerNotification? {
        if (active != null || durationSeconds <= 0) return null
        val triggerCount = ceil(durationSeconds.toDouble() * 1_000.0 / STAY_MS).toInt().coerceAtLeast(1)
        val now = elapsedRealtime()
        return ActiveBannerNotification(
            sessionId = ++nextSessionId,
            notificationId = notificationId,
            newsId = content.id,
            content = content,
            remainingTriggers = triggerCount,
            accumulatedStayMs = 0L,
            expiresAtElapsedMs = now + triggerCount * STAY_MS,
            lastShownAtElapsedMs = now,
            hasPlayedAlert = false,
        ).also { active = it }
    }

    @Synchronized
    fun markShown(sessionId: Long): BannerNotificationDispatch? {
        val current = active?.takeIf { it.sessionId == sessionId && it.remainingTriggers > 0 } ?: return null
        val updated = current.copy(
            remainingTriggers = current.remainingTriggers - 1,
            hasPlayedAlert = true,
        )
        active = updated
        return BannerNotificationDispatch(updated, isFirstInSession = !current.hasPlayedAlert)
    }

    @Synchronized
    fun finishStay(sessionId: Long): Boolean {
        val current = active?.takeIf { it.sessionId == sessionId } ?: return false
        val now = elapsedRealtime()
        val updated = current.copy(
            accumulatedStayMs = current.accumulatedStayMs + (now - current.lastShownAtElapsedMs).coerceAtLeast(0),
            lastShownAtElapsedMs = now,
        )
        return if (updated.remainingTriggers > 0) {
            active = updated
            true
        } else {
            active = null
            false
        }
    }

    @Synchronized
    fun cancel(sessionId: Long? = null): ActiveBannerNotification? {
        val current = active?.takeIf { sessionId == null || it.sessionId == sessionId } ?: return null
        active = null
        return current
    }

    @Synchronized
    fun isActive(): Boolean = active != null

    @Synchronized
    fun snapshot(): ActiveBannerNotification? = active

    companion object {
        const val STAY_MS = 1_500L
    }
}
