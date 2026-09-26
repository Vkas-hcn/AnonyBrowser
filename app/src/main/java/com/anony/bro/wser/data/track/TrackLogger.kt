package com.anony.bro.wser.data.track

/**
 * 日志抽象，便于在纯 JVM 单元测试中运行核心逻辑，同时在 Android 侧接入 android.util.Log。
 */
fun interface TrackLogger {
    fun log(level: Level, message: String, error: Throwable?)

    enum class Level { DEBUG, WARN, ERROR }

    companion object {
        /** 默认打到标准输出，避免核心逻辑依赖 Android。 */
        val DEFAULT = TrackLogger { level, message, error ->
            println("[TrackCache][$level] $message" + (error?.let { " :: $it" } ?: ""))
        }
    }
}

internal fun TrackLogger.d(message: String) = log(TrackLogger.Level.DEBUG, message, null)
internal fun TrackLogger.w(message: String, error: Throwable? = null) =
    log(TrackLogger.Level.WARN, message, error)
internal fun TrackLogger.e(message: String, error: Throwable? = null) =
    log(TrackLogger.Level.ERROR, message, error)
