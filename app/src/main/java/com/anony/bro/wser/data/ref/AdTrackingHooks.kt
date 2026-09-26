package com.anony.bro.wser.data.ref

import android.util.Log
import com.google.android.gms.ads.ResponseInfo

/**
 * 单个广告请求/展示实例的埋点上下文。
 * 由 [AdTrackingHooks.onRequest] 创建，随广告实例在缓存与展示之间传递。
 */
data class AdTrackInfo(
    val adType: String,
    val posId: String,
    var adId: String,
    var adSource: String = "",
    var requestStartMs: Long = 0L,
    var showStartMs: Long = 0L,
    var impressionReported: Boolean = false,
    var closeReported: Boolean = false,
)

/**
 * 广告生命周期埋点钩子：集中编排 [AdTrackingHelper] 的六个 BI 方法。
 * 广告管理中心只在 SDK 回调节点调用一行，埋点异常不影响广告主流程。
 */
object AdTrackingHooks {
    const val SDK_NAME = "AdMob"
    private const val TAG = "AdTrackingHelper"

    fun resolveAdSource(
        adSourceName: String?,
        mediationAdapterClassName: String?,
    ): String {
        return adSourceName?.takeIf { it.isNotBlank() }
            ?: mediationAdapterClassName.orEmpty()
    }

    fun resolveAdSource(responseInfo: ResponseInfo?): String {
        return resolveAdSource(
            responseInfo?.loadedAdapterResponseInfo?.adSourceName,
            responseInfo?.mediationAdapterClassName,
        )
    }

    fun stayDurationSeconds(showStartMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        if (showStartMs <= 0L) return 0L
        return ((nowMs - showStartMs) / 1000L).coerceAtLeast(0L)
    }

    fun onRequest(adType: String, posId: String): AdTrackInfo {
        val requestStartMs = System.currentTimeMillis()
        val adId = runCatching {
            AdTrackingHelper.trackAdRequestGMA(adType, posId)
        }.getOrDefault("")
        Log.d(TAG, "【埋点】广告请求 | adType=$adType posId=$posId adId=$adId")
        return AdTrackInfo(
            adType = adType,
            posId = posId,
            adId = adId,
            requestStartMs = requestStartMs,
        )
    }

    fun onFilled(info: AdTrackInfo?, responseInfo: ResponseInfo?) {
        info ?: return
        runCatching {
            info.adSource = resolveAdSource(responseInfo)
            val duration = (System.currentTimeMillis() - info.requestStartMs).coerceAtLeast(0L)
            AdTrackingHelper.trackAdFilledWithSource(
                info.adType,
                info.posId,
                info.adId,
                SDK_NAME,
                info.adSource,
                duration,
            )
            Log.d(
                TAG,
                "【埋点】广告填充 | adType=${info.adType} posId=${info.posId} adId=${info.adId} " +
                    "sdk=$SDK_NAME adSource=${info.adSource} duration=${duration}ms",
            )
        }
    }

    fun onFailed(
        adType: String,
        posId: String,
        info: AdTrackInfo?,
        code: String,
        msg: String,
    ) {
        runCatching {
            AdTrackingHelper.trackAdFailed(
                adType,
                posId,
                info?.adId.orEmpty(),
                code,
                msg,
            )
            Log.d(
                TAG,
                "【埋点】广告失败 | adType=$adType posId=$posId adId=${info?.adId.orEmpty()} " +
                    "errCode=$code errMsg=$msg",
            )
        }
    }

    fun onImpression(info: AdTrackInfo?) {
        info ?: return
        if (info.impressionReported) return
        runCatching {
            info.impressionReported = true
            info.showStartMs = System.currentTimeMillis()
            AdTrackingHelper.trackAdImpressionWithSource(
                info.adType,
                info.posId,
                info.adId,
                SDK_NAME,
                info.adSource,
            )
            Log.d(
                TAG,
                "【埋点】广告曝光 | adType=${info.adType} posId=${info.posId} adId=${info.adId} " +
                    "sdk=$SDK_NAME adSource=${info.adSource}",
            )
        }
    }

    fun onClick(info: AdTrackInfo?) {
        info ?: return
        runCatching {
            AdTrackingHelper.trackAdClickWithSource(
                info.adType,
                info.posId,
                info.adId,
                SDK_NAME,
                info.adSource,
            )
            Log.d(
                TAG,
                "【埋点】广告点击 | adType=${info.adType} posId=${info.posId} adId=${info.adId} " +
                    "sdk=$SDK_NAME adSource=${info.adSource}",
            )
        }
    }

    fun onClose(info: AdTrackInfo?) {
        info ?: return
        if (info.closeReported) return
        runCatching {
            info.closeReported = true
            val stayDuration = stayDurationSeconds(info.showStartMs)
            AdTrackingHelper.trackAdClose(
                info.adType,
                info.posId,
                info.adId,
                stayDuration,
            )
            Log.d(
                TAG,
                "【埋点】广告关闭 | adType=${info.adType} posId=${info.posId} adId=${info.adId} " +
                    "stayDuration=${stayDuration}s",
            )
        }
    }
}
