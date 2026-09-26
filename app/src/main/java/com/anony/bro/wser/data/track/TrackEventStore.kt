package com.anony.bro.wser.data.track

import java.util.concurrent.ConcurrentHashMap

/**
 * 平台专属缓存队列的存储层：内存缓存 + 持久化兜底。
 *
 * - 内存层：以平台为维度维护有序事件列表，读写快速。
 * - 持久化层：每次变更后落盘（[TrackEventPersistence]），防止进程被杀/页面刷新导致数据丢失。
 * - 初始化时自动从持久化层恢复，并在恢复过程中顺带清理过期数据。
 *
 * 所有写操作都会执行：过期清理 -> 容量淘汰 -> 落盘。
 * 该类不依赖任何 Android API，可直接在单元测试中运行。
 */
class TrackEventStore(
    private val persistence: TrackEventPersistence = TrackEventPersistence.InMemory(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxCapacity: Int = TrackCachePolicy.DEFAULT_MAX_CAPACITY,
    private val ttlMillis: Long = TrackCachePolicy.DEFAULT_TTL_MILLIS,
    private val logger: TrackLogger = TrackLogger.DEFAULT,
) {
    private val queues = ConcurrentHashMap<TrackPlatform, MutableList<PendingTrackEvent>>()
    private val locks = TrackPlatform.entries.associateWith { Any() }

    init {
        // 进程/页面重建后，从持久化层恢复缓存队列（顺带清理过期数据）。
        TrackPlatform.entries.forEach { platform ->
            val restored = PendingTrackEvent.listFromJson(persistence.read(platform))
            if (restored.isNotEmpty()) {
                val (valid, _) = TrackCachePolicy.partitionExpired(restored, clock(), ttlMillis)
                queues[platform] = valid.toMutableList()
                if (valid.size != restored.size) persist(platform)
                logger.d("restored ${valid.size} events for $platform (dropped ${restored.size - valid.size} expired)")
            }
        }
    }

    private fun queueOf(platform: TrackPlatform): MutableList<PendingTrackEvent> =
        queues.getOrPut(platform) { mutableListOf() }

    private fun lockOf(platform: TrackPlatform): Any = locks.getValue(platform)

    /** 追加一条事件，写入前执行过期清理与容量淘汰，随后落盘。返回是否写入成功。 */
    fun append(platform: TrackPlatform, event: PendingTrackEvent): Boolean = synchronized(lockOf(platform)) {
        runCatching {
            val queue = queueOf(platform)
            queue.add(event)
            val (afterExpiry, expired) = TrackCachePolicy.partitionExpired(queue, clock(), ttlMillis)
            val (kept, dropped) = TrackCachePolicy.enforceCapacity(afterExpiry, maxCapacity)
            if (expired.isNotEmpty() || dropped.isNotEmpty()) {
                logger.w("cache trim on append[$platform]: expired=${expired.size} evicted=${dropped.size}")
            }
            queue.clear()
            queue.addAll(TrackCachePolicy.sortedByTime(kept))
            persist(platform)
            true
        }.getOrElse {
            // 缓存写入失败（含存储空间不足）时输出错误日志，保证异常不外泄。
            logger.e("append failed for $platform event=${event.event}", it)
            false
        }
    }

    /** 返回某平台按时序排序、且未过期的事件快照。 */
    fun snapshot(platform: TrackPlatform): List<PendingTrackEvent> = synchronized(lockOf(platform)) {
        val (valid, expired) = TrackCachePolicy.partitionExpired(queueOf(platform), clock(), ttlMillis)
        if (expired.isNotEmpty()) {
            queueOf(platform).apply { clear(); addAll(valid) }
            persist(platform)
        }
        TrackCachePolicy.sortedByTime(valid)
    }

    /** 补发成功后按 id 精确移除已上报事件，避免重复上报。 */
    fun removeByIds(platform: TrackPlatform, ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lockOf(platform)) {
            val idSet = ids.toHashSet()
            val queue = queueOf(platform)
            val changed = queue.removeAll { it.id in idSet }
            if (changed) persist(platform)
        }
    }

    /** 主动清理所有平台的过期数据，供定时任务调用。 */
    fun purgeExpired() {
        TrackPlatform.entries.forEach { platform ->
            synchronized(lockOf(platform)) {
                val (valid, expired) = TrackCachePolicy.partitionExpired(queueOf(platform), clock(), ttlMillis)
                if (expired.isNotEmpty()) {
                    queueOf(platform).apply { clear(); addAll(valid) }
                    persist(platform)
                    logger.d("purged ${expired.size} expired events for $platform")
                }
            }
        }
    }

    fun size(platform: TrackPlatform): Int = synchronized(lockOf(platform)) { queueOf(platform).size }

    private fun persist(platform: TrackPlatform) {
        runCatching {
            persistence.write(platform, PendingTrackEvent.listToJson(queueOf(platform)))
        }.onFailure {
            // 持久化失败不影响内存队列继续工作，仅记录日志。
            logger.e("persist failed for $platform", it)
        }
    }
}
