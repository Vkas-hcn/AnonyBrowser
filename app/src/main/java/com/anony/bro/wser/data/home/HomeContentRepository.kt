package com.anony.bro.wser.data.home

import android.content.Context
import androidx.core.content.edit
import com.anony.bro.wser.R
import com.anony.bro.wser.data.NetTool
import com.anony.bro.wser.data.news.NewsRepository
import com.anony.bro.wser.data.recommendations.RecommendationRegion
import com.anony.bro.wser.data.recommendations.WebsiteCategory
import com.anony.bro.wser.data.recommendations.WebsiteRecommendationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.random.Random

/** Keeps the Banner independent from any one provider and only returns renderable items. */
class HomeContentRepository(
    private val newsRepository: NewsRepository = NewsRepository(),
) {
    fun cached(context: Context): List<HomeContentItem> = Cache(context).read()

    suspend fun refresh(context: Context, region: RecommendationRegion): List<HomeContentItem> = coroutineScope {
        val appContext = context.applicationContext
        val news = async { news(appContext, region) }
        val weather = async { weather(appContext, region) }
        val exchange = async { exchange(appContext, region) }
        val curiosity = async { curiosity() }
        val items = awaitAll(news, weather, exchange, curiosity).flatten()
        val selected = select(items)
        if (selected.isNotEmpty()) Cache(appContext).write(selected)
        selected
    }

    private suspend fun news(context: Context, region: RecommendationRegion): List<HomeContentItem> {
        val articles = (newsRepository.load(context, region) as? NewsRepository.NewsLoadResult.Content)
            ?.articles.orEmpty()
        val enriched = newsRepository.enrichImages(context, articles)
        return enriched.mapNotNull { article -> article.imageUrl?.let { url ->
            HomeContentItem(article.id, HomeContentType.NEWS, article.title, article.sourceName,
                BannerVisual.Remote(url), article.url, article.sourceName, article.publishedAtMs, 50)
        } }
    }

    private suspend fun weather(context: Context, region: RecommendationRegion): List<HomeContentItem> {
        val location = NetTool.fetchLocation(context) ?: return emptyList()
        val raw = get("https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,weather_code") ?: return emptyList()
        val current = JSONObject(raw).optJSONObject("current") ?: return emptyList()
        val temperature = current.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return emptyList()
        val condition = weatherCondition(current.optInt("weather_code", -1))
        val website = WebsiteRecommendationRepository.recommendations(context, region)[WebsiteCategory.WEATHER]
            ?.firstOrNull() ?: return emptyList()
        return listOf(HomeContentItem("weather:${location.countryCode}", HomeContentType.WEATHER,
            "${location.city.ifBlank { location.countryCode }} ${temperature.toInt()}°C", condition.label,
            BannerVisual.Local(condition.resId), website.url, website.name, priority = 90))
    }

    private suspend fun exchange(context: Context, region: RecommendationRegion): List<HomeContentItem> {
        val quote = currencyFor(region.countryCode)
        if (quote == "USD") return emptyList()
        val raw = get("https://api.frankfurter.app/latest?from=USD&to=$quote") ?: return emptyList()
        val rate = JSONObject(raw).optJSONObject("rates")?.optDouble(quote, Double.NaN)
            ?.takeIf { !it.isNaN() } ?: return emptyList()
        val website = WebsiteRecommendationRepository.recommendations(context, region)[WebsiteCategory.EXCHANGE]
            ?.firstOrNull() ?: return emptyList()
        return listOf(HomeContentItem("exchange:USD$quote", HomeContentType.EXCHANGE, "USD / $quote",
            "1 USD = ${"%.3f".format(Locale.US, rate)} $quote", BannerVisual.Local(exchangeVisual()),
            website.url, website.name, priority = 80))
    }

    private suspend fun curiosity(): List<HomeContentItem> {
        val raw = get("https://en.wikipedia.org/api/rest_v1/page/random/summary") ?: return emptyList()
        val json = JSONObject(raw)
        val title = json.optString("title").trim()
        val description = json.optString("extract").trim()
        val image = json.optJSONObject("thumbnail")?.optString("source")
        val url = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
        if (title.isBlank() || description.isBlank() || image.isNullOrBlank() || url.isNullOrBlank()) return emptyList()
        return listOf(HomeContentItem("wiki:$url", HomeContentType.CURIOSITY, title, description,
            BannerVisual.Remote(image), url, "Wikipedia", priority = 70))
    }

    private fun select(items: List<HomeContentItem>): List<HomeContentItem> {
        val byType = items.filter { it.title.isNotBlank() }.distinctBy { it.id }.groupBy { it.type }
        val result = mutableListOf<HomeContentItem>()
        listOf(HomeContentType.NEWS, HomeContentType.NEWS, HomeContentType.WEATHER,
            HomeContentType.EXCHANGE, HomeContentType.CURIOSITY).forEach { type ->
            byType[type]?.firstOrNull { it !in result }?.let(result::add)
        }
        (byType[HomeContentType.NEWS].orEmpty() + byType[HomeContentType.CURIOSITY].orEmpty() + items)
            .filter { it !in result }.sortedByDescending { it.priority }.forEach { if (result.size < 5) result += it }
        // Keep a small reserve so the Adapter can replace a remote image that fails to decode.
        return (result + items.filter { it !in result }.sortedByDescending { it.priority }).take(10)
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) { runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000; setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "GateBrowser/1.0") }
        try { if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null } finally { connection.disconnect() }
    }.getOrNull() }

    private data class WeatherVisual(val label: String, val resId: Int)
    private fun weatherCondition(code: Int) = when (code) {
        0 -> WeatherVisual("Sunny", R.drawable.weather_sunny); 1, 2 -> WeatherVisual("Partly cloudy", R.drawable.weather_partly_cloudy)
        3, 45, 48 -> WeatherVisual(if (code == 3) "Cloudy" else "Fog", R.drawable.weather_cloudy)
        71, 73, 75, 77, 85, 86 -> WeatherVisual("Snow", R.drawable.weather_snow); 95, 96, 99 -> WeatherVisual("Thunderstorm", R.drawable.weather_thunderstorm)
        else -> WeatherVisual("Rain", R.drawable.weather_rain)
    }
    private fun exchangeVisual(): Int = if (Random.nextBoolean()) R.drawable.exchange_default else R.drawable.exchange_default_2
    private fun currencyFor(country: String?) = mapOf("SG" to "SGD", "JP" to "JPY", "CN" to "CNY", "GB" to "GBP", "AU" to "AUD", "CA" to "CAD", "DE" to "EUR", "FR" to "EUR", "IT" to "EUR", "ES" to "EUR")[country] ?: "USD"

    private class Cache(context: Context) {
        private val prefs = context.applicationContext.getSharedPreferences("home_content", Context.MODE_PRIVATE)
        fun read(): List<HomeContentItem> = runCatching { prefs.getString("items", null)?.let(::JSONArray)?.let { array -> buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.let(::item)?.takeUnless(::isLegacyApiLink)?.let(::add) } }.orEmpty() }.getOrDefault(emptyList())
        fun write(items: List<HomeContentItem>) = prefs.edit { putString("items", JSONArray().apply { items.forEach { put(JSONObject().apply { put("id", it.id); put("type", it.type.name); put("title", it.title); put("subtitle", it.subtitle); put("target", it.targetUrl); put("source", it.source); put("time", it.publishTime); put("priority", it.priority); when (val visual = it.visual) { is BannerVisual.Remote -> { put("remote", visual.url) }; is BannerVisual.Local -> put("local", visual.resId) } }) } }.toString()) }
        private fun item(json: JSONObject): HomeContentItem? = runCatching { val type = HomeContentType.valueOf(json.getString("type")); val visual = json.optString("remote").takeIf { it.startsWith("http") }?.let(BannerVisual::Remote) ?: json.optInt("local").takeIf { it != 0 }?.let(BannerVisual::Local) ?: return null; HomeContentItem(json.getString("id"), type, json.getString("title"), json.optString("subtitle"), visual, json.getString("target"), json.optString("source"), json.optLong("time").takeIf { it > 0 }, json.optInt("priority")) }.getOrNull()
        private fun isLegacyApiLink(item: HomeContentItem): Boolean = item.type in setOf(HomeContentType.WEATHER, HomeContentType.EXCHANGE) && (item.targetUrl.contains("open-meteo.com") || item.targetUrl.contains("frankfurter.app"))
    }
}
