package com.anony.bro.wser.data.track

/**
 * 缓存策略的纯函数集合：过期清理、容量淘汰、时序排序。
 * 不依赖任何 Android API，便于单元测试。
 */
object TrackCachePolicy {

    /** 默认过期时间：7 天。 */
    const val DEFAULT_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** 每个平台队列默认最大容量。 */
    const val DEFAULT_MAX_CAPACITY: Int = 500

    /** 默认最大重试次数（初始上报之外的额外尝试次数）。 */
    const val DEFAULT_MAX_RETRIES: Int = 3

    /** 按触发时间升序排序，保证补发时的事件时序。 */
    fun sortedByTime(events: List<PendingTrackEvent>): List<PendingTrackEvent> =
        events.sortedBy { it.timestamp }

    /** 过滤掉超过 [ttlMillis] 的过期事件；返回 (有效事件, 已过期事件)。 */
    fun partitionExpired(
        events: List<PendingTrackEvent>,
        now: Long,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
    ): Pair<List<PendingTrackEvent>, List<PendingTrackEvent>> =
        events.partition { now - it.timestamp < ttlMillis }

    /**
     * 容量淘汰：超过 [maxCapacity] 时按时序丢弃最旧的事件（FIFO）。
     * 返回 (保留事件, 被淘汰事件)。
     */
    fun enforceCapacity(
        events: List<PendingTrackEvent>,
        maxCapacity: Int = DEFAULT_MAX_CAPACITY,
    ): Pair<List<PendingTrackEvent>, List<PendingTrackEvent>> {
        if (maxCapacity <= 0 || events.size <= maxCapacity) return events to emptyList()
        val ordered = sortedByTime(events)
        val overflow = ordered.size - maxCapacity
        return ordered.drop(overflow) to ordered.take(overflow)
    }
}
