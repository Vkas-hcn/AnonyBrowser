package com.anony.bro.wser.data.news

import android.util.Xml
import com.anony.bro.wser.data.recommendations.RecommendationRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class GoogleNewsRssProvider : NewsProvider {
    override val name: String = "Google News RSS"

    override suspend fun fetch(region: RecommendationRegion): List<NewsArticle> =
        withContext(Dispatchers.IO) {
            val country = region.countryCode?.uppercase(Locale.US) ?: "US"
            val language = region.languageCode.ifBlank { "en" }.lowercase(Locale.US)
            val url = "https://news.google.com/rss?hl=$language-$country&gl=$country&ceid=$country:$language"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                check(connection.responseCode in 200..299) { "News request failed" }
                connection.inputStream.use { parse(it, region, name) }
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(
        input: java.io.InputStream,
        region: RecommendationRegion,
        providerName: String,
    ): List<NewsArticle> {
        val parser = Xml.newPullParser().apply {
            setInput(input, Charsets.UTF_8.name())
        }
        val articles = mutableListOf<NewsArticle>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                parseItem(parser, region, providerName)?.let(articles::add)
            }
            event = parser.next()
        }
        return articles
    }

    private fun parseItem(
        parser: XmlPullParser,
        region: RecommendationRegion,
        providerName: String,
    ): NewsArticle? {
        var title = ""
        var url = ""
        var description: String? = null
        var source = ""
        var publishedAtMs: Long? = null
        var imageUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "item")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parser.nextText().trim()
                    "link" -> url = parser.nextText().trim()
                    "description" -> {
                        description = parser.nextText().trim().ifBlank { null }
                        imageUrl = description?.let(::imageUrlFromDescription)
                    }
                    "pubDate" -> publishedAtMs = parser.nextText().trim().toPublishedAtMs()
                    "source" -> source = parser.nextText().trim()
                }
            }
            parser.next()
        }

        if (title.isBlank() || url.isBlank()) return null
        return NewsArticle(
            id = "$providerName:$url",
            title = title,
            imageUrl = ImageUrlValidator.valid(imageUrl),
            sourceName = source.ifBlank { "News" },
            publishedAtMs = publishedAtMs,
            url = url,
            description = description?.replace(IMAGE_TAG, "")?.trim()?.ifBlank { null },
            countryCode = region.countryCode,
            languageCode = region.languageCode,
            category = "General",
            providerName = providerName,
            imageSource = ImageUrlValidator.valid(imageUrl)?.let { NewsImageSource.API },
        )
    }

    private fun imageUrlFromDescription(description: String): String? =
        IMAGE_SRC.find(description)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    private fun String.toPublishedAtMs(): Long? = runCatching {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val USER_AGENT = "GateBrowser/1.0"
        val IMAGE_SRC = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val IMAGE_TAG = Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE)
    }
}
