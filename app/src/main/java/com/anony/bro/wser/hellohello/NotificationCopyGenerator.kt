package com.anony.bro.wser.hellohello

import com.anony.bro.wser.data.recommendations.WebsiteCategory
import kotlin.random.Random

data class NotificationCopyData(
    val count: String? = null,
    val topic: String? = null,
    val country: String? = null,
    val city: String? = null,
    val temp: String? = null,
    val rate: String? = null,
    val percent: String? = null,
)

data class NotificationCopy(
    val newsTitle: String,
    val newsAction: String,
)

object NotificationCopyGenerator {
    private const val DEFAULT_CACHE_NAMESPACE = "default"
    private const val DEFAULT_COUNT = "many"
    private const val DEFAULT_TOPIC = "today's top story"
    private const val DEFAULT_COUNTRY = "your area"
    private const val DEFAULT_CITY = "your area"
    private const val DEFAULT_TEMP = "20"
    private const val DEFAULT_RATE = "unavailable"
    private const val DEFAULT_PERCENT = "0%"
    private const val CURRENCY_PAIR = "USD/EUR"

    private val templates = mapOf(
        WebsiteCategory.NEWS to listOf(
            "🔥 {count} people are reading this now",
            "👀 Everyone's talking about this",
            "🌎 What's happening today",
            "👀 Don't miss this",
            "⚡ Just in: {topic}",
            "🌍 What's trending in {country}",
        ),
        WebsiteCategory.WEATHER to listOf(
            "☀️ Today's weather in {city}",
            "🌦️ Weather is changing in {city}",
            "☀️ Good morning, {city}",
            "🌧️ Rain may be on the way",
            "🌡️ It's {temp}° in {city}",
        ),
        WebsiteCategory.EXCHANGE to listOf(
            "💱 {currency_pair} rate updated",
            "📈 {currency_pair} moved {percent}",
            "💱 What's the {currency_pair} rate today?",
            "✈️ Traveling soon?",
            "👀 Did {currency_pair} move today?",
        ),
        WebsiteCategory.CURIOSITY to listOf(
            "🤔 Can you guess what's trending today?",
            "👀 You might want to see this",
            "🔥 Just now！See what happened",
            "🧐 What do you think is #1 today?",
            "😮 This is getting attention",
        ),
    )

    private val actions = mapOf(
        WebsiteCategory.NEWS to "See What's Hot",
        WebsiteCategory.WEATHER to "Check Forecast",
        WebsiteCategory.EXCHANGE to "Check Now",
        WebsiteCategory.CURIOSITY to "Find Out",
    )

    private val lastTemplateByNamespace = mutableMapOf<String, MutableMap<WebsiteCategory, String>>()

    @Synchronized
    fun generate(
        category: WebsiteCategory,
        data: NotificationCopyData = NotificationCopyData(),
        random: Random = Random.Default,
        cacheNamespace: String = DEFAULT_CACHE_NAMESPACE,
    ): NotificationCopy {
        val available = templates.getValue(category)
        val cache = lastTemplateByNamespace.getOrPut(cacheNamespace) { mutableMapOf() }
        val previous = cache[category]
        val candidates = available.filterNot { it == previous }.ifEmpty { available }
        val template = candidates.random(random)
        cache[category] = template
        return NotificationCopy(
            newsTitle = template.replacePlaceholders(data),
            newsAction = actions.getValue(category),
        )
    }

    @Synchronized
    fun reset(cacheNamespace: String? = null) {
        if (cacheNamespace == null) lastTemplateByNamespace.clear()
        else lastTemplateByNamespace.remove(cacheNamespace)
    }

    internal fun templatesFor(category: WebsiteCategory): List<String> = templates.getValue(category)

    private fun String.replacePlaceholders(data: NotificationCopyData): String =
        replace("{count}", data.count.orDefault(DEFAULT_COUNT))
            .replace("{topic}", data.topic.orDefault(DEFAULT_TOPIC))
            .replace("{country}", data.country.orDefault(DEFAULT_COUNTRY))
            .replace("{city}", data.city.orDefault(DEFAULT_CITY))
            .replace("{temp}", data.temp.orDefault(DEFAULT_TEMP))
            .replace("{currency_pair}", CURRENCY_PAIR)
            .replace("{rate}", data.rate.orDefault(DEFAULT_RATE))
            .replace("{percent}", data.percent.orDefault(DEFAULT_PERCENT))

    private fun String?.orDefault(default: String): String = takeUnless { it.isNullOrBlank() } ?: default
}
