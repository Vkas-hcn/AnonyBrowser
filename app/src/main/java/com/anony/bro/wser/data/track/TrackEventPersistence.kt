package com.anony.bro.wser.data.track

/**
 * 持久化兜底抽象层：以平台为维度读写序列化后的缓存数据。
 *
 * Android 生产实现使用 SharedPreferences（等价于 Web 的 localStorage），
 * 单元测试可注入内存实现来验证“页面刷新/进程重建后缓存恢复”的能力。
 */
interface TrackEventPersistence {
    /** 读取某平台的持久化缓存（JSON 数组字符串），无数据返回 null。 */
    fun read(platform: TrackPlatform): String?

    /** 写入某平台的持久化缓存。json 为空数组时代表清空。 */
    fun write(platform: TrackPlatform, json: String)

    /** 内存实现，主要用于测试与降级兜底（持久化不可用时仍可工作）。 */
    class InMemory : TrackEventPersistence {
        private val store = HashMap<TrackPlatform, String>()
        override fun read(platform: TrackPlatform): String? = store[platform]
        override fun write(platform: TrackPlatform, json: String) {
            store[platform] = json
        }
    }
}
