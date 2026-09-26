package com.anony.bro.wser.data.news

import android.net.Uri
import com.anony.bro.wser.data.recommendations.RecommendationRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class NewsDataProvider(
    private val apiKey: String,
) : NewsProvider {
    override val name: String = "NewsData.io"

    override suspend fun fetch(region: RecommendationRegion): List<NewsArticle> =
        withContext(Dispatchers.IO) {
            val country = region.countryCode?.lowercase(Locale.US) ?: "us"
            val language = region.languageCode.ifBlank { "en" }.lowercase(Locale.US)
            val url = Uri.Builder()
                .scheme("https")
                .authority("newsdata.io")
                .appendPath("api")
                .appendPath("1")
                .appendPath("latest")
                .appendQueryParameter("apikey", apiKey)
                .appendQueryParameter("country", country)
                .appendQueryParameter("language", language)
                .appendQueryParameter("category", "top,world,technology,business")
                .build()
                .toString()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) return@withContext emptyList()
                parse(connection.inputStream.bufferedReader().use { it.readText() }, region)
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(raw: String, region: RecommendationRegion): List<NewsArticle> = runCatching {
        JSONObject(raw).optJSONArray("results").toArticles(region)
    }.getOrDefault(emptyList())

    private fun JSONArray?.toArticles(region: RecommendationRegion): List<NewsArticle> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toArticle(region)?.let(::add)
            }
        }
    }

    private fun JSONObject.toArticle(region: RecommendationRegion): NewsArticle? {
        val title = optString("title").trim()
        val link = optString("link").trim()
        if (title.isBlank() || !link.isHttpUrl()) return null
        return NewsArticle(
            id = optString("article_id").trim().ifBlank { link },
            title = title,
            imageUrl = ImageUrlValidator.valid(optString("image_url")),
            sourceName = optString("source_name").trim()
                .ifBlank { optString("source_id").trim() }
                .ifBlank { "News" },
            publishedAtMs = optString("pubDate").toEpochMs(),
            url = link,
            description = optString("description").trim().ifBlank { null },
            countryCode = region.countryCode,
            languageCode = optString("language").trim().ifBlank { region.languageCode },
            category = optJSONArray("category").firstString().ifBlank { "General" },
            providerName = name,
            imageSource = ImageUrlValidator.valid(optString("image_url"))?.let { NewsImageSource.API },
        )
    }

    private fun String.isHttpUrl(): Boolean = runCatching {
        val uri = java.net.URI(this)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun String.toEpochMs(): Long? {
        val value = trim()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
    }

    private fun JSONArray?.firstString(): String {
        val array = this ?: return ""
        return (0 until array.length())
            .asSequence()
            .map { array.optString(it).trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val USER_AGENT = "GateBrowser/1.0"
    }
}
