package com.anony.bro.wser.data.track

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 埋点缓存补发系统的核心编排器。
 *
 * 职责：
 * 1. 维护每个平台的初始化状态（[markInitialized]）。
 * 2. 打点分发：已初始化的平台直接上报；未初始化的平台进入该平台专属缓存队列。
 * 3. 平台 SDK 初始化完成后触发补发：按触发时间顺序依次上报，失败重试，成功后清理缓存。
 *
 * 该类不依赖任何 Android API，可直接在单元测试中验证全部核心逻辑。
 * 上报动作本身通过 [TrackEventUploader] 注入，SDK 初始化状态通过 [markInitialized] 驱动，
 * 因此可以模拟“SDK 延迟初始化”“网络失败重试”等场景。
 */
class TrackEventCacheManager(
    private val store: TrackEventStore,
    private val uploaders: Map<TrackPlatform, TrackEventUploader>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val maxRetries: Int = TrackCachePolicy.DEFAULT_MAX_RETRIES,
    /** 单次重试之间的等待回调（毫秒）。默认无等待，便于测试；Android 侧可注入退避策略。 */
    private val backoff: (attempt: Int) -> Unit = {},
    private val logger: TrackLogger = TrackLogger.DEFAULT,
) {
    private val initialized: MutableMap<TrackPlatform, AtomicBoolean> =
        TrackPlatform.entries.associateWith { AtomicBoolean(false) }.let(::ConcurrentHashMap)

    private val replayLocks = TrackPlatform.entries.associateWith { Any() }

    fun isInitialized(platform: TrackPlatform): Boolean =
        initialized.getValue(platform).get()

    /**
     * 打点入口。对每个目标平台独立判定：已初始化直接上报（失败则转入缓存兜底），
     * 未初始化则写入该平台缓存队列，等待补发。
     */
    fun track(
        event: String,
        properties: Map<String, String> = emptyMap(),
        eventType: String = PendingTrackEvent.TYPE_CUSTOM,
        platforms: Collection<TrackPlatform> = TrackPlatform.entries,
    ) {
        val pending = PendingTrackEvent(
            id = idGenerator(),
            event = event,
            eventType = eventType,
            properties = properties,
            timestamp = clock(),
        )
        platforms.forEach { platform ->
            val uploader = uploaders[platform]
            if (uploader == null) {
                logger.w("trackEvent[${platform.id}] event=${pending.event} skipped: no uploader")
                return@forEach
            }
            val initialized = isInitialized(platform)
            if (initialized) {
                // 已初始化：直接上报；若上报失败则退回缓存，等待下次补发，提升成功率。
                logger.d("trackEvent[${platform.id}] event=${pending.event} initialized=true -> direct upload")
                if (uploadWithRetry(platform, uploader, pending)) {
                    logger.d("trackEvent[${platform.id}] event=${pending.event} direct upload success")
                } else {
                    logger.w("trackEvent[${platform.id}] event=${pending.event} direct upload failed, fallback to cache")
                    store.append(platform, pending)
                }
            } else {
                logger.d("trackEvent[${platform.id}] event=${pending.event} initialized=false -> cached, wait for replay")
                store.append(platform, pending)
            }
        }
    }

    /**
     * 标记某平台 SDK 初始化完成，并立即触发该平台缓存的补发。
     * 幂等：重复调用只会触发额外的补发，不会重复置位。
     */
    fun markInitialized(platform: TrackPlatform): ReplayResult {
        initialized.getValue(platform).set(true)
        logger.d("platform initialized: $platform")
        return replay(platform)
    }

    /**
     * 补发某平台缓存队列。按触发时间顺序依次上报，成功的事件从缓存中移除。
     * 若平台尚未初始化则不补发。
     */
    fun replay(platform: TrackPlatform): ReplayResult = synchronized(replayLocks.getValue(platform)) {
        if (!isInitialized(platform)) return ReplayResult.skipped()
        val uploader = uploaders[platform] ?: return ReplayResult.skipped()

        val events = store.snapshot(platform) // 已按时序排序且过滤过期
        if (events.isEmpty()) return ReplayResult(0, 0, 0)

        val uploadedIds = ArrayList<String>(events.size)
        var failed = 0
        for (event in events) {
            if (uploadWithRetry(platform, uploader, event)) {
                uploadedIds.add(event.id)
            } else {
                failed++
                // 失败的事件保留在缓存中，等待下一次补发触发；不中断后续事件的上报。
                logger.w("replay upload failed[$platform], kept for later: ${event.event}")
            }
        }
        store.removeByIds(platform, uploadedIds)
        ReplayResult(total = events.size, succeeded = uploadedIds.size, failed = failed).also {
            logger.d("replay done[$platform]: $it")
        }
    }

    /** 主动清理所有平台的过期缓存（>TTL），供定时/前台时机调用。 */
    fun purgeExpired() = store.purgeExpired()

    /** 上报单条事件，最多重试 [maxRetries] 次（初始尝试之外）。异常被捕获并视为失败。 */
    private fun uploadWithRetry(
        platform: TrackPlatform,
        uploader: TrackEventUploader,
        event: PendingTrackEvent,
    ): Boolean {
        var attempt = 0
        while (true) {
            val ok = runCatching { uploader.upload(event) }.getOrElse {
                logger.e("upload threw[$platform] event=${event.event} attempt=$attempt", it)
                false
            }
            if (ok) return true
            if (attempt >= maxRetries) return false
            attempt++
            runCatching { backoff(attempt) }
        }
    }

    /** 补发结果，便于观测与测试。 */
    data class ReplayResult(
        val total: Int,
        val succeeded: Int,
        val failed: Int,
        val skipped: Boolean = false,
    ) {
        companion object {
            fun skipped() = ReplayResult(total = 0, succeeded = 0, failed = 0, skipped = true)
        }
    }
}
