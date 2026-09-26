package com.anony.bro.wser.data.trending

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Google Trends Trending RSS（非官方稳定 API）。
 * 例：https://trends.google.com/trending/rss?geo=US
 */
class GoogleTrendsRssSource {
    suspend fun fetch(countryCode: String): List<String> = withContext(Dispatchers.IO) {
        val geo = countryCode.uppercase(Locale.US).takeIf { it.matches(COUNTRY) } ?: "US"
        val connection = (URL("$BASE_URL$geo").openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            check(connection.responseCode in 200..299) {
                "Trends HTTP ${connection.responseCode}"
            }
            connection.inputStream.use(::parseTitles)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTitles(input: java.io.InputStream): List<String> {
        val parser = Xml.newPullParser().apply {
            setInput(input, Charsets.UTF_8.name())
        }
        val titles = mutableListOf<String>()
        var event = parser.eventType
        var inItem = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) {
                        parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(titles::add)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") inItem = false
            }
            event = parser.next()
        }
        return titles
    }

    private companion object {
        const val BASE_URL = "https://trends.google.com/trending/rss?geo="
        const val TIMEOUT_MS = 8_000
        const val USER_AGENT = "GateBrowser/1.0"
        val COUNTRY = Regex("[A-Z]{2}")
    }
}
