package com.anony.bro.wser.data.home

import androidx.annotation.DrawableRes

enum class HomeContentType { NEWS, WEATHER, EXCHANGE, CURIOSITY }

sealed interface BannerVisual {
    data class Remote(val url: String) : BannerVisual
    data class Local(@DrawableRes val resId: Int) : BannerVisual
}

data class HomeContentItem(
    val id: String,
    val type: HomeContentType,
    val title: String,
    val subtitle: String,
    val visual: BannerVisual,
    val targetUrl: String,
    val source: String,
    val publishTime: Long? = null,
    val priority: Int = 0,
)
