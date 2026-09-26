package com.anony.bro.wser.data.recommendations

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

enum class WebsiteCategory(val label: String, val iconResName: String) {
    NEWS("News", "ic_notify_news"),
    WEATHER("Weather", "ic_locations"),
    EXCHANGE("Exchange", "ic_net_menu"),
    CURIOSITY("Curiosity", "ic_wikipedia"),
}

data class RecommendedWebsite(
    val id: String,
    val name: String,
    val url: String,
    val iconResName: String,
    val description: String,
    val categories: Set<WebsiteCategory>,
    val countries: Set<String>,
    val languages: Set<String>,
    val priority: Int,
    val global: Boolean,
    val official: Boolean,
)

object WebsiteRecommendationRepository {
    private const val ASSET_NAME = "recommended_websites.json"
    private const val PER_CATEGORY_LIMIT = 3

    fun recommendations(context: Context, region: RecommendationRegion): Map<WebsiteCategory, List<RecommendedWebsite>> {
        val websites = load(context)
        return WebsiteCategory.entries.associateWith { category ->
            websites.asSequence()
                .filter { category in it.categories }
                .filter { isEligible(it, region) }
                .sortedWith(
                    compareByDescending<RecommendedWebsite> { score(it, region) }
                        .thenByDescending { it.priority }
                        .thenBy { it.name },
                )
                .take(PER_CATEGORY_LIMIT)
                .toList()
        }
    }

    fun allRecommendations(context: Context, region: RecommendationRegion): Map<WebsiteCategory, List<RecommendedWebsite>> {
        val websites = load(context)
        return WebsiteCategory.entries.associateWith { category ->
            websites.asSequence()
                .filter { category in it.categories }
                .filter { isEligible(it, region) }
                .sortedWith(
                    compareByDescending<RecommendedWebsite> { score(it, region) }
                        .thenByDescending { it.priority }
                        .thenBy { it.name },
                )
                .toList()
        }
    }

    fun randomRecommendation(context: Context, region: RecommendationRegion): RecommendedWebsite? =
        recommendations(context, region)
            .values
            .flatten()
            .distinctBy { it.id }
            .randomOrNull()

    internal fun rank(websites: List<RecommendedWebsite>, category: WebsiteCategory, region: RecommendationRegion): List<RecommendedWebsite> =
        websites.asSequence()
            .filter { category in it.categories && isEligible(it, region) }
            .sortedWith(compareByDescending<RecommendedWebsite> { score(it, region) }.thenByDescending { it.priority })
            .toList()

    private fun load(context: Context): List<RecommendedWebsite> = runCatching {
        val root = context.assets.open(ASSET_NAME).bufferedReader().use { JSONObject(it.readText()) }
        root.optJSONArray("websites").toWebsites()
    }.getOrDefault(emptyList())

    private fun JSONArray?.toWebsites(): List<RecommendedWebsite> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                item.toWebsite()?.let(::add)
            }
        }
    }

    private fun JSONObject.toWebsite(): RecommendedWebsite? {
        val id = optString("id").trim()
        val name = optString("name").trim()
        val url = optString("url").trim()
        val categories = optStringSet("categories", lowercase = false).mapNotNull { raw ->
            WebsiteCategory.entries.firstOrNull { it.name == raw }
        }.toSet()
        if (!optBoolean("enabled", true) || id.isBlank() || name.isBlank() || !url.isHttpUrl() || categories.isEmpty()) return null
        return RecommendedWebsite(
            id = id,
            name = name,
            url = url,
            iconResName = optString("icon").trim().ifBlank { categories.first().iconResName },
            description = optString("description").trim(),
            categories = categories,
            countries = optStringSet("countries"),
            languages = optStringSet("languages"),
            priority = optInt("priority", 0),
            global = optBoolean("global", false),
            official = optBoolean("official", false),
        )
    }

    private fun JSONObject.optStringSet(name: String, lowercase: Boolean = true): Set<String> {
        val array = optJSONArray(name) ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).trim().let {
                    if (lowercase) it.lowercase(Locale.US) else it.uppercase(Locale.US)
                }.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun isEligible(website: RecommendedWebsite, region: RecommendationRegion): Boolean =
        website.global ||
            region.countryCode?.lowercase(Locale.US) in website.countries

    private fun score(website: RecommendedWebsite, region: RecommendationRegion): Int {
        val isLocal = region.countryCode?.lowercase(Locale.US) in website.countries
        val languageMatch = region.languageCode in website.languages
        return website.priority +
            if (isLocal) 10_000 else 0 +
            if (languageMatch) 1_000 else 0 +
            if (website.official && isLocal) 500 else 0 +
            if (website.global) 100 else 0
    }

    private fun String.isHttpUrl(): Boolean = runCatching {
        URI(this).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
    }.getOrDefault(false)
}
