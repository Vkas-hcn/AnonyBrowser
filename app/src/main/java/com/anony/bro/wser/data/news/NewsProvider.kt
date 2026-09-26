package com.anony.bro.wser.data.news

import com.anony.bro.wser.data.recommendations.RecommendationRegion

interface NewsProvider {
    val name: String

    suspend fun fetch(region: RecommendationRegion): List<NewsArticle>
}
