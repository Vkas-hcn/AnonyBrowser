package com.anony.bro.wser.ads

import com.google.android.gms.ads.nativead.NativeAd


interface AdLoadListener {

    fun onAdLoaded()

    fun onAdFailedToLoad(error: String)
}


interface AdShowListener {

    fun onAdShowed()
    

    fun onAdShowFailed(error: String)
    

    fun onAdClosed()
    

    fun onAdClicked()
}


interface NativeAdLoadListener {

    fun onNativeAdLoaded(nativeAd: NativeAd)
    

    fun onAdFailedToLoad(error: String)
}

interface RewardedAdShowListener : AdShowListener {

    fun onUserEarnedReward(type: String, amount: Int)
}
