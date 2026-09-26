package com.anony.bro.wser.data.history

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "browser_history",
)

/**
 * 基于 DataStore 的浏览历史持久化。
 *
 * - 每次成功访问都追加一条新记录，不因同 URL 自动覆盖 / 删除旧记录
 * - 仅在用户明确删除或超出容量上限时移除记录
 * - 读写失败时降级为空列表，不抛到 UI；写入前若内存为空会再读盘，避免误清空
 */
class HistoryRepository private constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var memoryCache: List<HistoryEntry>? = null

    fun loadBlocking(): List<HistoryEntry> {
        memoryCache?.let { return it }
        return runBlocking { loadInternal() }
    }

    suspend fun load(): List<HistoryEntry> = loadInternal()

    fun addAsync(
        title: String,
        url: String,
        visitedAt: Long = System.currentTimeMillis(),
        faviconPath: String? = null,
    ) {
        if (url.isBlank() || url.startsWith("about:", ignoreCase = true)) return
        scope.launch {
            runCatching {
                mutex.withLock {
                    val current = loadUnlocked().toMutableList()
                    current.add(
                        0,
                        HistoryEntry(
                            id = UUID.randomUUID().toString(),
                            title = title.ifBlank { url },
                            url = url,
                            visitedAt = visitedAt,
                            faviconPath = faviconPath,
                        ),
                    )
                    val trimmed = current.take(MAX_ENTRIES)
                    memoryCache = trimmed
                    persistUnlocked(trimmed)
                }
            }.onFailure { Log.w(TAG, "addAsync failed", it) }
        }
    }

    fun deleteByIdsAsync(ids: Collection<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            runCatching {
                mutex.withLock {
                    val next = loadUnlocked().filterNot { it.id in ids }
                    memoryCache = next
                    persistUnlocked(next)
                }
            }.onFailure { Log.w(TAG, "deleteByIdsAsync failed", it) }
        }
    }

    fun deleteByIdsBlocking(ids: Collection<String>) {
        if (ids.isEmpty()) return
        runBlocking {
            runCatching {
                mutex.withLock {
                    val next = loadUnlocked().filterNot { it.id in ids }
                    memoryCache = next
                    persistUnlocked(next)
                }
            }.onFailure { Log.w(TAG, "deleteByIdsBlocking failed", it) }
        }
    }

    fun clearAllBlocking() {
        runBlocking {
            runCatching {
                mutex.withLock {
                    memoryCache = emptyList()
                    persistUnlocked(emptyList())
                }
            }.onFailure { Log.w(TAG, "clearAllBlocking failed", it) }
        }
    }

    private suspend fun loadInternal(): List<HistoryEntry> = mutex.withLock {
        loadUnlocked()
    }

    private suspend fun loadUnlocked(): List<HistoryEntry> {
        memoryCache?.let { return it }
        val entries = withContext(Dispatchers.IO) {
            runCatching {
                val prefs = dataStore.data
                    .catch { error ->
                        if (error is IOException) {
                            Log.w(TAG, "DataStore read error", error)
                            emit(emptyPreferences())
                        } else {
                            throw error
                        }
                    }
                    .first()
                val raw = prefs[KEY_HISTORY_JSON]
                // 有原始数据但解码失败时不要当成空列表写入缓存，避免后续 add 覆盖丢数据
                if (raw.isNullOrBlank()) {
                    emptyList()
                } else {
                    HistoryCodec.decode(raw) ?: run {
                        Log.w(TAG, "History JSON decode failed, keeping empty until valid write")
                        emptyList()
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed to load history", it)
            }.getOrNull()
        }
        val resolved = entries.orEmpty()
        memoryCache = resolved
        return resolved
    }

    private suspend fun persistUnlocked(entries: List<HistoryEntry>) {
        withContext(Dispatchers.IO) {
            val json = HistoryCodec.encode(entries)
            dataStore.edit { prefs ->
                prefs[KEY_HISTORY_JSON] = json
            }
        }
    }

    companion object {
        private const val TAG = "HistoryRepository"
        private const val MAX_ENTRIES = 500
        private val KEY_HISTORY_JSON = stringPreferencesKey("history_json")

        @Volatile
        private var instance: HistoryRepository? = null

        fun get(context: Context): HistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: HistoryRepository(
                    context.applicationContext.historyDataStore,
                ).also { instance = it }
            }
        }
    }
}
