package com.anony.bro.wser.data.news

data class NewsArticle(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val sourceName: String,
    val publishedAtMs: Long?,
    val url: String,
    val description: String?,
    val countryCode: String?,
    val languageCode: String,
    val category: String,
    val providerName: String,
    val imageSource: NewsImageSource? = null,
) {
    val hasImage: Boolean
        get() = !imageUrl.isNullOrBlank()
}

enum class NewsImageSource {
    API, CACHE, OG_IMAGE, TWITTER_IMAGE, JSON_LD, ARTICLE_IMAGE, WEBVIEW,
}
