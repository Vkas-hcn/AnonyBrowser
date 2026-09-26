package com.anony.bro.wser.data.track

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条缓存的埋点事件，保留完整的埋点参数、触发时间与事件类型等核心信息。
 *
 * @param id 事件唯一标识，用于补发成功后精确移除，避免重复上报。
 * @param event 埋点事件名。
 * @param eventType 事件类型（如 custom / revenue / lifecycle），便于分类与排查。
 * @param properties 完整的埋点参数。
 * @param timestamp 埋点触发时间（epoch millis），补发时按此字段排序保证时序。
 * @param retryCount 已重试次数，用于观测与排查。
 */
data class PendingTrackEvent(
    val id: String,
    val event: String,
    val eventType: String,
    val properties: Map<String, String>,
    val timestamp: Long,
    val retryCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_EVENT, event)
        put(KEY_TYPE, eventType)
        put(KEY_TIME, timestamp)
        put(KEY_RETRY, retryCount)
        put(KEY_PROPS, JSONObject(properties.toMap()))
    }

    companion object {
        const val TYPE_CUSTOM = "custom"

        private const val KEY_ID = "id"
        private const val KEY_EVENT = "event"
        private const val KEY_TYPE = "type"
        private const val KEY_TIME = "time"
        private const val KEY_RETRY = "retry"
        private const val KEY_PROPS = "props"

        fun fromJson(obj: JSONObject): PendingTrackEvent {
            val propsJson = obj.optJSONObject(KEY_PROPS) ?: JSONObject()
            val props = buildMap<String, String> {
                val keys = propsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, propsJson.optString(key))
                }
            }
            return PendingTrackEvent(
                id = obj.optString(KEY_ID),
                event = obj.optString(KEY_EVENT),
                eventType = obj.optString(KEY_TYPE, TYPE_CUSTOM),
                properties = props,
                timestamp = obj.optLong(KEY_TIME),
                retryCount = obj.optInt(KEY_RETRY, 0),
            )
        }

        /** 将一组事件序列化为 JSON 数组字符串，用于持久化。 */
        fun listToJson(events: List<PendingTrackEvent>): String {
            val array = JSONArray()
            events.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        /** 从持久化的 JSON 数组字符串反序列化，解析失败时返回空列表（容错）。 */
        fun listFromJson(raw: String?): List<PendingTrackEvent> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(::fromJson)
                }
            }.getOrDefault(emptyList())
        }
    }
}
