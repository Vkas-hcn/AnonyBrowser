package com.anony.bro.wser.data.tab

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

private val Context.tabSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tab_session",
)

/**
 * 基于 Jetpack DataStore 的多 Tab 会话持久化。
 *
 * - 写入失败 / 读取损坏时降级为默认空白会话，不抛到 UI 层
 * - [saveAsync] 用于生命周期节点；[saveBlocking] 用于进程即将结束时尽量落盘
 */
class TabSessionRepository private constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var memoryCache: TabSessionSnapshot? = null

    fun loadBlocking(): TabSessionSnapshot {
        memoryCache?.let { return it }
        return runBlocking { loadInternal() }
    }

    suspend fun load(): TabSessionSnapshot = loadInternal()

    fun saveAsync(snapshot: TabSessionSnapshot) {
        memoryCache = snapshot
        scope.launch {
            runCatching { persist(snapshot) }
                .onFailure { Log.w(TAG, "Async tab session save failed", it) }
        }
    }

    fun saveBlocking(snapshot: TabSessionSnapshot) {
        memoryCache = snapshot
        runBlocking {
            runCatching { persist(snapshot) }
                .onFailure { Log.w(TAG, "Blocking tab session save failed", it) }
        }
    }

    fun clearAsync() {
        memoryCache = null
        scope.launch {
            runCatching {
                dataStore.edit { it.remove(KEY_SESSION_JSON) }
            }.onFailure { Log.w(TAG, "Clear tab session failed", it) }
        }
    }

    fun clearBlocking() {
        memoryCache = null
        runBlocking {
            runCatching {
                dataStore.edit { it.remove(KEY_SESSION_JSON) }
            }.onFailure { Log.w(TAG, "Clear tab session blocking failed", it) }
        }
    }

    private suspend fun loadInternal(): TabSessionSnapshot = mutex.withLock {
        memoryCache?.let { return it }
        val snapshot = withContext(Dispatchers.IO) {
            runCatching {
                val prefs = dataStore.data
                    .catch { error ->
                        if (error is IOException) {
                            Log.w(TAG, "DataStore read error, using empty preferences", error)
                            emit(emptyPreferences())
                        } else {
                            throw error
                        }
                    }
                    .first()
                val raw = prefs[KEY_SESSION_JSON]
                TabSessionCodec.decode(raw)
            }.onFailure {
                Log.w(TAG, "Failed to load tab session, falling back to empty", it)
            }.getOrNull()
        }
        val resolved = snapshot?.takeUnless { it.tabs.isEmpty() } ?: TabSessionSnapshot.EMPTY
        memoryCache = resolved
        resolved
    }

    private suspend fun persist(snapshot: TabSessionSnapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val json = TabSessionCodec.encode(snapshot)
            dataStore.edit { prefs ->
                prefs[KEY_SESSION_JSON] = json
            }
        }
    }

    companion object {
        private const val TAG = "TabSessionRepository"
        private val KEY_SESSION_JSON = stringPreferencesKey("session_json")

        @Volatile
        private var instance: TabSessionRepository? = null

        fun get(context: Context): TabSessionRepository {
            return instance ?: synchronized(this) {
                instance ?: TabSessionRepository(
                    context.applicationContext.tabSessionDataStore,
                ).also { instance = it }
            }
        }

        /** 仅测试用：注入自定义 DataStore */
        internal fun createForTest(dataStore: DataStore<Preferences>): TabSessionRepository =
            TabSessionRepository(dataStore)
    }
}
