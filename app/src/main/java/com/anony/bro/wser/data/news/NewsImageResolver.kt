package com.anony.bro.wser.data.news

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Resolves only a public article's lead-image URL; it never reads article content or cookies. */
class NewsImageResolver(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun resolve(article: NewsArticle): NewsArticle {
        ImageUrlValidator.valid(article.imageUrl)?.let {
            return article.copy(imageUrl = it, imageSource = article.imageSource ?: NewsImageSource.API)
        }
        cached(article.url)?.let { return article.copy(imageUrl = it, imageSource = NewsImageSource.CACHE) }
        val found = withContext(Dispatchers.IO) { extract(article.url) } ?: return article
        prefs.edit { putString(key(article.url), found.first) }
        return article.copy(imageUrl = found.first, imageSource = found.second)
    }

    private fun cached(articleUrl: String): String? =
        prefs.getString(key(articleUrl), null)?.let(ImageUrlValidator::valid)

    private fun extract(articleUrl: String): Pair<String, NewsImageSource>? = runCatching {
        val connection = (URL(articleUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299 || !connection.contentType.orEmpty().contains("html", true)) return null
            val html = connection.inputStream.bufferedReader().use { it.readText().take(MAX_HTML_CHARS) }
            meta(html, "property", "og:image")?.let { resolveUrl(articleUrl, it) }?.let { return it to NewsImageSource.OG_IMAGE }
            meta(html, "name", "twitter:image")?.let { resolveUrl(articleUrl, it) }?.let { return it to NewsImageSource.TWITTER_IMAGE }
            meta(html, "property", "twitter:image")?.let { resolveUrl(articleUrl, it) }?.let { return it to NewsImageSource.TWITTER_IMAGE }
            jsonLd(html)?.let { resolveUrl(articleUrl, it) }?.let { return it to NewsImageSource.JSON_LD }
            articleImage(html, articleUrl)?.let { return it to NewsImageSource.ARTICLE_IMAGE }
            null
        } finally { connection.disconnect() }
    }.getOrNull()

    private fun meta(html: String, attribute: String, value: String): String? {
        val tag = Regex("""<meta\\b[^>]*\\b$attribute\\s*=\\s*([\"'])${Regex.escape(value)}\\1[^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return null
        return Regex("""\\bcontent\\s*=\\s*([\"'])(.*?)\\1""", RegexOption.IGNORE_CASE).find(tag)
            ?.groupValues?.get(2)
    }

    private fun jsonLd(html: String): String? = Regex("""<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html).firstNotNullOfOrNull { match -> imageFromJson(match.groupValues[1]) }

    private fun imageFromJson(raw: String): String? = runCatching {
        fun find(value: Any?): String? = when (value) {
            is JSONObject -> listOf("image", "thumbnailUrl").firstNotNullOfOrNull { find(value.opt(it)) }
                ?: (0 until value.length()).firstNotNullOfOrNull { find(value.opt(value.names()?.optString(it))) }
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { find(value.opt(it)) }
            is String -> ImageUrlValidator.valid(value)
            else -> null
        }
        find(JSONObject(raw))
    }.getOrNull() ?: runCatching { imageFromJson("{\"image\":${JSONArray(raw)} }") }.getOrNull()

    private fun articleImage(html: String, baseUrl: String): String? {
        val scoped = Regex("""<(article|main)\\b[^>]*>(.*?)</\\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(2) ?: html
        return Regex("""<img\\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(scoped).firstNotNullOfOrNull { image ->
            val tag = image.value
            listOf("data-src", "data-original", "src").firstNotNullOfOrNull { attribute ->
                Regex("""\\b$attribute\\s*=\\s*([\"'])(.*?)\\1""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)
            }?.let { raw -> resolveUrl(baseUrl, raw) }
        }
    }

    private fun resolveUrl(baseUrl: String, raw: String): String? =
        runCatching { URL(URL(baseUrl), raw).toString() }.getOrNull()?.let(ImageUrlValidator::valid)

    private fun key(url: String) = "image:$url"
    private companion object { const val PREFS_NAME = "news_image_urls"; const val TIMEOUT_MS = 8_000; const val MAX_HTML_CHARS = 512_000; const val USER_AGENT = "GateBrowser/1.0" }
}

object ImageUrlValidator {
    private val unwanted = Regex("logo|avatar|favicon|sprite|tracking|pixel|banner-ad|advertisement|[?&](w|width|h|height)=1(?:&|$)", RegexOption.IGNORE_CASE)
    fun valid(value: String?): String? {
        val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && !unwanted.containsMatchIn(it) }
    }
}
