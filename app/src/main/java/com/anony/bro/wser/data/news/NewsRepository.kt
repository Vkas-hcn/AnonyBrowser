package com.anony.bro.wser.data.news

import android.content.Context
import androidx.core.content.edit
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.data.recommendations.RecommendationRegion
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class NewsRepository(
    private val providers: List<NewsProvider> = defaultProviders(),
) {
    suspend fun load(context: Context, region: RecommendationRegion): NewsLoadResult {
        val cache = NewsCache(context)
        val cached = cache.read(region)
        if (cached?.isFresh == true &&
            NewsSelector.select(cached.articles).size >= MIN_BANNER_ITEMS &&
            (!hasImageProvider || cached.articles.any { it.hasImage })
        ) {
            return NewsLoadResult.Content(cached.articles, fromCache = true)
        }

        val remote = fetchProviders(region)
        val withFallback = if (NewsSelector.select(remote).size >= MIN_BANNER_ITEMS || region == GLOBAL_REGION) {
            remote
        } else {
            remote + fetchProviders(GLOBAL_REGION)
        }
        val selected = NewsSelector.select(withFallback)
        if (selected.isNotEmpty()) {
            cache.write(region, selected)
            return NewsLoadResult.Content(selected, fromCache = false)
        }
        return cached?.articles?.takeIf { it.isNotEmpty() }
            ?.let { NewsLoadResult.Content(it, fromCache = true) }
            ?: NewsLoadResult.Empty
    }

    /** Runs after the first Banner render; individual failures deliberately keep their placeholder. */
    suspend fun enrichImages(context: Context, articles: List<NewsArticle>): List<NewsArticle> = coroutineScope {
        val resolver = NewsImageResolver(context)
        val semaphore = Semaphore(MAX_IMAGE_REQUESTS)
        articles.map { article -> async {
            semaphore.withPermit { runCatching { resolver.resolve(article) }.getOrDefault(article) }
        } }.awaitAll()
    }

    private suspend fun fetchProviders(region: RecommendationRegion): List<NewsArticle> {
        val result = mutableListOf<NewsArticle>()
        providers.forEach { provider ->
            result += runCatching { provider.fetch(region) }.getOrDefault(emptyList())
            if (NewsSelector.select(result).size >= MIN_BANNER_ITEMS) return result
        }
        return result
    }

    private val hasImageProvider: Boolean
        get() = providers.any { it is NewsDataProvider }

    sealed interface NewsLoadResult {
        data class Content(val articles: List<NewsArticle>, val fromCache: Boolean) : NewsLoadResult
        data object Empty : NewsLoadResult
    }

    private class NewsCache(context: Context) {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun read(region: RecommendationRegion): CachedNews? = runCatching {
            val raw = prefs.getString(KEY_PAYLOAD, null) ?: return null
            val root = JSONObject(raw)
            if (root.optString("country") != region.countryCode.orEmpty() ||
                root.optString("language") != region.languageCode
            ) return null
            val articles = root.optJSONArray("articles").toArticles()
            if (articles.isEmpty()) return null
            CachedNews(articles, root.optLong("savedAtMs"))
        }.getOrNull()

        fun write(region: RecommendationRegion, articles: List<NewsArticle>) {
            val payload = JSONObject().apply {
                put("country", region.countryCode.orEmpty())
                put("language", region.languageCode)
                put("savedAtMs", System.currentTimeMillis())
                put("articles", JSONArray().apply { articles.forEach { put(it.toJson()) } })
            }
            prefs.edit { putString(KEY_PAYLOAD, payload.toString()) }
        }

        private fun JSONArray?.toArticles(): List<NewsArticle> {
            val array = this ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toArticle()?.let(::add)
                }
            }
        }

        private fun JSONObject.toArticle(): NewsArticle? {
            val title = optString("title").trim()
            val url = optString("url").trim()
            if (title.isBlank() || !isHttpUrl(url)) return null
            return NewsArticle(
                id = optString("id").trim().ifBlank { url },
                title = title,
                imageUrl = optString("imageUrl").trim().ifBlank { null },
                sourceName = optString("sourceName").trim().ifBlank { "News" },
                publishedAtMs = optLong("publishedAtMs").takeIf { it > 0L },
                url = url,
                description = optString("description").trim().ifBlank { null },
                countryCode = optString("countryCode").trim().ifBlank { null },
                languageCode = optString("languageCode").trim().ifBlank { "en" },
                category = optString("category").trim().ifBlank { "General" },
                providerName = optString("providerName").trim().ifBlank { "Cached" },
                imageSource = optString("imageSource").toImageSource(),
            )
        }

        private fun NewsArticle.toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("imageUrl", imageUrl)
            put("sourceName", sourceName)
            put("publishedAtMs", publishedAtMs)
            put("url", url)
            put("description", description)
            put("countryCode", countryCode)
            put("languageCode", languageCode)
            put("category", category)
            put("providerName", providerName)
            put("imageSource", imageSource?.name)
        }

        data class CachedNews(
            val articles: List<NewsArticle>,
            val savedAtMs: Long,
        ) {
            val isFresh: Boolean
                get() = savedAtMs > 0 && System.currentTimeMillis() - savedAtMs < CACHE_TTL_MS
        }
    }

    private companion object {
        const val PREFS_NAME = "home_news"
        const val KEY_PAYLOAD = "payload"
        const val CACHE_TTL_MS = 30 * 60 * 1_000L
        const val MIN_BANNER_ITEMS = 5
        const val MAX_IMAGE_REQUESTS = 3
        val GLOBAL_REGION = RecommendationRegion("US", "en", "Global")

        fun defaultProviders(): List<NewsProvider> = buildList {
            BuildConfig.NEWSDATA_API_KEY.takeIf { it.isNotBlank() }?.let(::NewsDataProvider)?.let(::add)
            add(GoogleNewsRssProvider())
        }
    }
}

private fun String.toImageSource(): NewsImageSource? =
    runCatching { NewsImageSource.valueOf(this) }.getOrNull()

object NewsSelector {
    fun select(candidates: List<NewsArticle>, limit: Int = 10): List<NewsArticle> {
        val ids = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val titles = mutableSetOf<String>()
        val unique = candidates.asSequence()
            .filter { it.title.isNotBlank() && isHttpUrl(it.url) }
            .sortedWith(
                compareByDescending<NewsArticle> { it.hasImage }
                    .thenByDescending { it.publishedAtMs ?: Long.MIN_VALUE },
            )
            .filter { article ->
                val id = article.id.lowercase(Locale.US)
                val url = article.url.lowercase(Locale.US)
                val title = article.title.lowercase(Locale.US)
                ids.add(id) && urls.add(url) && titles.add(title)
            }
            .toList()
        val diverse = mutableListOf<NewsArticle>()
        val sources = mutableSetOf<String>()
        unique.forEach { article ->
            if (sources.add(article.sourceName.lowercase(Locale.US))) diverse += article
        }
        unique.forEach { article ->
            if (article !in diverse) diverse += article
        }
        return diverse.take(limit)
    }

    private fun isHttpUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

private fun isHttpUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)
