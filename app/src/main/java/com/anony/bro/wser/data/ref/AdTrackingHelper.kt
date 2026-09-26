package com.anony.bro.wser.data.ref

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.flux.tracksdk.utils.UsdFromAdMicros
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.ResponseInfo
import com.google.firebase.analytics.FirebaseAnalytics
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.app.GateBrowserApplication
import java.util.Locale.getDefault
import java.util.TimeZone
import java.util.UUID

/**
 * 广告埋点辅助类
 * 用于统一管理广告相关的埋点上报
 */
object AdTrackingHelper {
    private const val TAG = "AdTrackingHelper"

    private val revenueLock = Any()
    private const val PREF_NAME = "ad_tracking_prefs"
    private const val KEY_TOTAL_ADS_REVENUE = "total_ads_revenue_001_accumulated"

    /**
     * 广告类型
     */
    object AdType {
        const val BANNER = "banner"
        const val NATIVE = "native"
        const val INTERSTITIAL = "interstitial"
        const val OPEN = "open"
        const val REWARDED = "rewarded"
    }

    /**
     * 上报广告请求（AdMob 聚合）
     */
    fun trackAdRequestGMA(
        adType: String,
        posId: String
    ): String {
        val adId = UUID.randomUUID().toString()
        TrackSDK.trackAdRequest(adType, posId, "AdMob", adId)
        return adId
    }

    /**
     * 上报广告填充成功（显式传入来源，适配 AdMob）
     */
    fun trackAdFilledWithSource(
        adType: String,
        posId: String,
        adId: String,
        sdkName: String,
        adSource: String,
        duration: Long
    ) {
        TrackSDK.trackAdFilled(adType, posId, sdkName, adId, adSource, duration)
    }

    /**
     * 上报广告填充失败
     * @param adType 广告类型
     * @param posId 位置ID
     * @param adId 唯一请求ID
     * @param errCode 错误码
     * @param errMsg 错误描述
     */
    fun trackAdFailed(
        adType: String,
        posId: String,
        adId: String,
        errCode: String,
        errMsg: String
    ) {
        // 将 errCode 转换为 Int，如果转换失败使用 -1
        val errCodeInt = errCode.toIntOrNull() ?: -1
        TrackSDK.trackAdFailed(adType, posId, "AdMob", adId, errCodeInt, errMsg)
    }

    /**
     * 上报广告展示（显式传入来源，适配 AdMob）
     */
    fun trackAdImpressionWithSource(
        adType: String,
        posId: String,
        adId: String,
        sdkName: String,
        adSource: String,
        isVisiblePct: Int = 100,
        scene: String = ""
    ) {
        TrackSDK.trackAdImpression(
            adType,
            posId,
            sdkName,
            adId,
            adSource,
            isVisiblePct.toFloat(),
            scene
        )
    }

    /**
     * 上报广告点击（显式传入来源，适配 AdMob）
     */
    fun trackAdClickWithSource(
        adType: String,
        posId: String,
        adId: String,
        sdkName: String,
        adSource: String,
        clickArea: String = ""
    ) {
        TrackSDK.trackAdClick(adType, posId, sdkName, adId, adSource, clickArea)
    }

    /**
     * 上报广告关闭
     * @param adType 广告类型
     * @param posId 位置ID
     * @param adId 唯一请求ID
     * @param stayDuration 展示多少秒后关闭
     */
    fun trackAdClose(
        adType: String,
        posId: String,
        adId: String,
        stayDuration: Long
    ) {
        TrackSDK.trackAdClose(adType, posId, adId, stayDuration)
        GateBrowserApplication.get().flushTrackEvents()
    }


    fun trackAdRevenueAdjust(
        adValue: AdValue,
        responseInfo: ResponseInfo?,
        adUnitId: String,
    ) {
        runCatching {
            val adRevenue = AdjustAdRevenue("admob_sdk")
            val valueM = if (BuildConfig.DEBUG) {
                6000L
            } else {
                adValue.valueMicros
            }
            val usd: Double = UsdFromAdMicros.toUsd(
                valueM,
                adValue.currencyCode.uppercase(getDefault()),
                null
            )
            if (usd < 0) return
            adRevenue.setRevenue(usd, "USD")
            adRevenue.setAdRevenueNetwork("Admob")
            adRevenue.setAdRevenueUnit(adUnitId)
            Adjust.trackAdRevenue(adRevenue)
            Log.d(
                TAG,
                "trackAdRevenueAdjust usd=$usd unit=$adUnitId network=${responseInfo?.mediationAdapterClassName}"
            )
        }.onFailure {
            Log.e(TAG, "trackAdRevenueAdjust failed unit=$adUnitId", it)
        }
    }

    /**
     * 上报广告收益（AdMob OnPaidEvent，使用 micros）
     */
    fun trackAdImpressionRevenueMicros(
        context: Context,
        adValue: AdValue,
        adType: String,
        posId: String,
    ) {
        val valueM = if (BuildConfig.DEBUG) {
            6000L
        } else {
            adValue.valueMicros
        }
        val originalCurrency = adValue.currencyCode.uppercase(getDefault())
        val revenue: Double = UsdFromAdMicros.toUsd(valueM, originalCurrency, null)
        if (revenue < 0) return
        val currencyCode = "USD"
        trackRevenueEvent(
            event = "ad_impression_revenue",
            revenue = revenue,
            currencyCode = currencyCode,
            adType = adType,
            posId = posId
        )
        trackTotalAdsRevenueIfNeeded(context, revenue)
        Log.d(TAG, "BI Firebase Facebook usd=$revenue adType=$adType")
    }


    /**
     * 各平台互相独立上报：任一平台抛出异常不得影响其余平台，否则排在后面的平台会被整条丢弃。
     */
    fun trackRevenueEvent(
        event: String,
        revenue: Double,
        currencyCode: String = "USD",
        adType: String,
        posId: String
    ) {
        //Firebase
        runCatching {
            putFirebaseAdRevenue(
                GateBrowserApplication.get(),
                event,
                revenue,
                currencyCode,
            )
        }.onFailure {
            Log.e(TAG, "trackRevenueEvent Firebase failed: $event", it)
        }
        //BI
        runCatching {
            TrackSDK.trackAdRevenue(revenue)
            Log.d(TAG, "trackRevenueEvent BI: $event revenue=$revenue currency=$currencyCode posId=$posId")
        }.onFailure {
            Log.e(TAG, "trackRevenueEvent BI failed: $event", it)
        }
        //Facebook：金额已是 USD，不再传原始币种二次换算
        runCatching {
            trackAdRevenueFacebook(adType, posId, revenue)
        }.onFailure {
            Log.e(TAG, "trackRevenueEvent Facebook failed: $event", it)
        }

    }


    private fun trackTotalAdsRevenueIfNeeded(
        context: Context,
        revenue: Double,
    ) {
        synchronized(revenueLock) {
            val prefs = context.applicationContext.trackingPrefs()
            val totalRevenue = prefs.getDouble(KEY_TOTAL_ADS_REVENUE, 0.0) + revenue
            if (totalRevenue >= 0.01) {
                trackValueEvent(
                    context = context,
                    event = "total_ads_revenue_001",
                    revenue = totalRevenue,
                    currencyCode = "USD"
                )
                prefs.putDouble(KEY_TOTAL_ADS_REVENUE, 0.0)
            } else {
                prefs.putDouble(KEY_TOTAL_ADS_REVENUE, totalRevenue)
            }
        }
    }





    private fun Context.trackingPrefs(): SharedPreferences {
        return applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun SharedPreferences.putDouble(key: String, value: Double) {
        edit { putLong(key, value.toBits()) }
    }

    private fun SharedPreferences.getDouble(key: String, defaultValue: Double): Double {
        if (!contains(key)) return defaultValue
        return Double.fromBits(getLong(key, defaultValue.toBits()))
    }

    private fun trackValueEvent(
        context: Context,
        event: String,
        revenue: Double,
        currencyCode: String = "USD"
    ) {
        putFirebaseAdRevenue(context, event, revenue, currencyCode)
        runCatching {
            TrackSDK.track(event, null, TrackPolicy.IMMEDIATE)
            Log.d(TAG, "trackValueEvent: $event revenue=$revenue currency=$currencyCode")
        }.onFailure {
            Log.e(TAG, "trackValueEvent failed: $event", it)
        }
    }

    /**
     * 上报 Facebook 广告价值。入参已是美元，币种固定 USD，不在此处二次换算。
     */
    fun trackAdRevenueFacebook(
        adType: String,
        posId: String,
        revenueUsd: Double,
        adSourceName: String? = null
    ) {
        val context = TrackSDK.getContext() ?: return
        if (!FacebookSdk.isInitialized()) return
        try {
            val fbParams = Bundle().apply {
                putString(AppEventsConstants.EVENT_PARAM_AD_TYPE, mapAdTypeForFacebook(adType))
                putString(AppEventsConstants.EVENT_PARAM_CURRENCY, "USD")
                putString(AppEventsConstants.EVENT_PARAM_CONTENT_ID, posId)
                adSourceName?.takeIf { it.isNotBlank() }?.let { putString("_ad_source", it) }
            }
            AppEventsLogger.newLogger(context).logEvent(
                AppEventsConstants.EVENT_NAME_AD_IMPRESSION,
                revenueUsd,
                fbParams
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun mapAdTypeForFacebook(adType: String): String {
        return when (adType) {
            AdType.BANNER -> "banner"
            AdType.NATIVE -> "native"
            AdType.INTERSTITIAL -> "interstitial"
            AdType.REWARDED -> "rewarded_video"
            AdType.OPEN -> "interstitial" // 开屏全屏，文档无单独类型时用 interstitial
            else -> adType.lowercase()
        }
    }

    fun putFirebaseAdRevenue(
        context: Context,
        event: String,
        revenue: Double,
        currencyCode: String = "USD",
    ) {
        runCatching {
            val bundle = Bundle().apply {
                putDouble(FirebaseAnalytics.Param.VALUE, revenue)
                putString(FirebaseAnalytics.Param.CURRENCY, currencyCode)
            }
            FirebaseAnalytics.getInstance(context).logEvent(event, bundle)
        }.onFailure {
            Log.e(TAG, "trackRevenueEvent failed: $event", it)
        }
    }
}
