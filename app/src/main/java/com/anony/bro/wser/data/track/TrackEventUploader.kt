package com.anony.bro.wser.data.track

/**
 * 平台上报器抽象。真正调用各平台埋点 SDK 的地方。
 * 返回 true 表示上报成功，false 或抛异常均视为失败（会触发重试/继续缓存）。
 */
fun interface TrackEventUploader {
    fun upload(event: PendingTrackEvent): Boolean
}
