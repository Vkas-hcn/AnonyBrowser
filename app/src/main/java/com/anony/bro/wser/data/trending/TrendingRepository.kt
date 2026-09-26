package com.anony.bro.wser.data.trending

import android.content.Context
import androidx.core.content.edit
import com.anony.bro.wser.data.DataHubTool
import com.anony.bro.wser.data.recommendations.RegionResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class TrendingRepository(
    private val source: GoogleTrendsRssSource = GoogleTrendsRssSource(),
    private val serverPayload: () -> String = { DataHubTool.vpnData },
) {
    private val fetchMutex = Mutex()

    suspend fun warmUp(context: Context) {
        val appContext = context.applicationContext
        val region = RegionResolver.resolve(appContext)
        val country = region.countryCode?.uppercase(Locale.US) ?: "US"
        val cache = TrendingCache(appContext)
        val cached = cache.read()
        if (cached == null) {
            load(appContext, forceNetwork = false)
            return
        }
        if (cached.countryCode != country || isOlderThanOneHour(cached.timestamp)) {
            refreshNetwork(appContext)
        }
    }

    suspend fun load(context: Context, forceNetwork: Boolean = false): TrendingLoadResult {
        val appContext = context.applicationContext
        val cache = TrendingCache(appContext)
        val cached = cache.read()

        // 有本地缓存先立刻返回，不阻塞在 IP 国家解析上
        if (!forceNetwork && cached != null && cached.keywords.isNotEmpty()) {
            val needsRefresh = !cached.isFresh
            return TrendingLoadResult(
                keywords = withConfiguredHotSearch(cached.keywords),
                countryCode = cached.countryCode,
                fromCache = true,
                loading = needsRefresh,
                stale = needsRefresh,
            )
        }

        val country = RegionResolver.resolve(appContext).countryCode?.uppercase(Locale.US) ?: "US"
        return fetchAndCache(country, cache, fallback = cached, forceNetwork = forceNetwork)
    }

    suspend fun refreshNetwork(context: Context): TrendingLoadResult {
        val appContext = context.applicationContext
        val country = RegionResolver.resolve(appContext).countryCode?.uppercase(Locale.US) ?: "US"
        val cache = TrendingCache(appContext)
        return fetchAndCache(country, cache, fallback = cache.read(), forceNetwork = true)
    }

    private suspend fun fetchAndCache(
        country: String,
        cache: TrendingCache,
        fallback: CachedTrending?,
        forceNetwork: Boolean,
    ): TrendingLoadResult = fetchMutex.withLock {
        val cached = cache.read()
        if (!forceNetwork && cached != null && cached.countryCode == country && cached.isFresh) {
            return TrendingLoadResult(
                keywords = withConfiguredHotSearch(cached.keywords),
                countryCode = cached.countryCode,
                fromCache = true,
                loading = false,
            )
        }
        val remote = runCatching { source.fetch(country) }
            .getOrDefault(emptyList())
            .let(::sanitize)
        if (remote.isNotEmpty()) {
            cache.write(country, remote)
            return TrendingLoadResult(
                keywords = withConfiguredHotSearch(remote),
                countryCode = country,
                fromCache = false,
                loading = false,
            )
        }
        val old = fallback?.keywords?.takeIf { it.isNotEmpty() }?.let(::sanitize)
        if (!old.isNullOrEmpty()) {
            return TrendingLoadResult(
                keywords = withConfiguredHotSearch(old),
                countryCode = fallback.countryCode,
                fromCache = true,
                loading = false,
            )
        }
        return TrendingLoadResult(
            keywords = withConfiguredHotSearch(DEFAULT_KEYWORDS),
            countryCode = country,
            fromCache = false,
            loading = false,
            isFallback = true,
        )
    }

    private fun withConfiguredHotSearch(keywords: List<String>): List<String> =
        prependHotSearch(keywords, hotSearch(serverPayload()))

    fun page(keywords: List<String>, pageIndex: Int, pageSize: Int = PAGE_SIZE): List<String> {
        if (keywords.isEmpty() || pageSize <= 0) return emptyList()
        val pageCount = pageCount(keywords.size, pageSize)
        val safePage = ((pageIndex % pageCount) + pageCount) % pageCount
        val start = safePage * pageSize
        return keywords.drop(start).take(pageSize)
    }

    fun pageCount(size: Int, pageSize: Int = PAGE_SIZE): Int =
        if (size <= 0 || pageSize <= 0) 1 else (size + pageSize - 1) / pageSize

    fun sanitize(raw: List<String>): List<String> {
        val result = ArrayList<String>(raw.size.coerceAtMost(MAX_KEYWORDS))
        val used = HashSet<String>()
        for (item in raw) {
            val cleaned = cleanKeyword(item) ?: continue
            if (used.add(cleaned.lowercase(Locale.US))) {
                result += cleaned
                if (result.size >= MAX_KEYWORDS) break
            }
        }
        return result
    }

    private fun cleanKeyword(raw: String): String? {
        var text = raw
            .replace(HTML_TAG, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(WHITESPACE, " ")
            .trim()
        if (text.isEmpty()) return null
        if (text.length > MAX_KEYWORD_LEN) text = text.take(MAX_KEYWORD_LEN).trim()
        if (text.length < 2) return null
        return text
    }

    data class TrendingLoadResult(
        val keywords: List<String>,
        val countryCode: String,
        val fromCache: Boolean,
        val loading: Boolean = false,
        val stale: Boolean = false,
        val isFallback: Boolean = false,
    )

    private class TrendingCache(context: Context) {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun read(): CachedTrending? = runCatching {
            val raw = prefs.getString(KEY_PAYLOAD, null) ?: return null
            val root = JSONObject(raw)
            val country = root.optString("countryCode").uppercase(Locale.US)
                .takeIf { it.matches(COUNTRY) } ?: return null
            val array = root.optJSONArray("keywords") ?: return null
            val keywords = buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            if (keywords.isEmpty()) return null
            CachedTrending(country, keywords, root.optLong("timestamp"))
        }.getOrNull()

        fun write(countryCode: String, keywords: List<String>) {
            val payload = JSONObject().apply {
                put("countryCode", countryCode)
                put("timestamp", System.currentTimeMillis())
                put("keywords", JSONArray().apply { keywords.forEach { put(it) } })
            }
            prefs.edit { putString(KEY_PAYLOAD, payload.toString()) }
        }
    }

    data class CachedTrending(
        val countryCode: String,
        val keywords: List<String>,
        val timestamp: Long,
    ) {
        val isFresh: Boolean
            get() = timestamp > 0L && !isOlderThanOneHour(timestamp)
    }

    companion object {
        private const val CACHE_TTL_MS = 60 * 60 * 1_000L
        const val PAGE_SIZE = 6
        const val MAX_KEYWORDS = 30
        private const val MAX_KEYWORD_LEN = 80
        private const val PREFS_NAME = "popular_searches"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_DATA = "data"
        private const val KEY_HOT_SEARCH = "hot_search"
        private val COUNTRY = Regex("[A-Z]{2}")
        private val HTML_TAG = Regex("<[^>]*>")
        private val WHITESPACE = Regex("\\s+")
        val shared: TrendingRepository by lazy { TrendingRepository() }

        val DEFAULT_KEYWORDS = listOf(
            "Trending",
            "News",
            "Weather",
            "Stock market",
            "Technology",
            "Sports",
            "Travel",
            "Entertainment",
            "Music",
            "Movies",
            "Health",
            "Shopping",
        )

        fun hotSearch(rawPayload: String): List<String> =
            extractHotSearchArray(rawPayload)?.let(::decodeStringArray).orEmpty()

        fun prependHotSearch(keywords: List<String>, hotSearch: List<String>): List<String> =
            if (hotSearch.isEmpty()) keywords else hotSearch + keywords

        fun isOlderThanOneHour(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
            timestampMs <= 0L || nowMs - timestampMs >= CACHE_TTL_MS

        private fun extractHotSearchArray(rawPayload: String): String? {
            val dataBlock = extractObjectBlock(rawPayload, KEY_DATA) ?: return null
            val keyIndex = dataBlock.indexOf("\"$KEY_HOT_SEARCH\"")
            if (keyIndex < 0) return null
            val colonIndex = dataBlock.indexOf(':', keyIndex)
            if (colonIndex < 0) return null
            val value = dataBlock.substring(colonIndex + 1).trimStart()
            if (value.startsWith("null", ignoreCase = true) ||
                value.startsWith("undefined", ignoreCase = true)
            ) {
                return null
            }
            val start = value.indexOf('[')
            if (start < 0) return null
            val end = findMatchingDelimiter(value, start, '[', ']') ?: return null
            return value.substring(start, end + 1)
        }

        private fun extractObjectBlock(text: String, key: String): String? {
            val keyIndex = text.indexOf("\"$key\"")
            if (keyIndex < 0) return null
            val colonIndex = text.indexOf(':', keyIndex)
            if (colonIndex < 0) return null
            val start = text.indexOf('{', colonIndex)
            if (start < 0) return null
            val end = findMatchingDelimiter(text, start, '{', '}') ?: return null
            return text.substring(start, end + 1)
        }

        private fun decodeStringArray(rawArray: String): List<String> {
            val result = mutableListOf<String>()
            var index = 0
            while (index < rawArray.length) {
                when (rawArray[index]) {
                    ' ', '\n', '\r', '\t', '[', ',' -> index += 1
                    ']' -> return result
                    '"' -> {
                        val parsed = readJsonString(rawArray, index) ?: return result
                        parsed.value.trim().takeIf { it.isNotEmpty() }?.let(result::add)
                        index = parsed.nextIndex
                    }
                    else -> {
                        index = rawArray.indexOfAny(charArrayOf(',', ']'), index)
                            .takeIf { it >= 0 }
                            ?: rawArray.length
                    }
                }
            }
            return result
        }

        private fun readJsonString(text: String, quoteIndex: Int): ParsedString? {
            val value = StringBuilder()
            var index = quoteIndex + 1
            var escaped = false
            while (index < text.length) {
                val ch = text[index]
                when {
                    escaped -> {
                        when (ch) {
                            '"', '\\', '/' -> value.append(ch)
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000C')
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            'u' -> {
                                val hex = text.substring(index + 1, (index + 5).coerceAtMost(text.length))
                                value.append(hex.toIntOrNull(16)?.toChar() ?: ch)
                                index += 4
                            }
                            else -> value.append(ch)
                        }
                        escaped = false
                    }
                    ch == '\\' -> escaped = true
                    ch == '"' -> return ParsedString(value.toString(), index + 1)
                    else -> value.append(ch)
                }
                index += 1
            }
            return null
        }

        private data class ParsedString(
            val value: String,
            val nextIndex: Int,
        )

        private fun findMatchingDelimiter(
            text: String,
            openIndex: Int,
            openChar: Char,
            closeChar: Char,
        ): Int? {
            var depth = 0
            var inString = false
            var escaped = false
            for (index in openIndex until text.length) {
                val ch = text[index]
                when {
                    inString && escaped -> escaped = false
                    inString && ch == '\\' -> escaped = true
                    inString && ch == '"' -> inString = false
                    !inString && ch == '"' -> inString = true
                    !inString && ch == openChar -> depth += 1
                    !inString && ch == closeChar -> {
                        depth -= 1
                        if (depth == 0) return index
                    }
                }
            }
            return null
        }
    }
}
