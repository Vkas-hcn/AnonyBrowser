package com.anony.bro.wser.data

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.edit
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsLogger
import com.flux.tracksdk.core.TrackSDK
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.data.ref.GDataRef
import com.anony.bro.wser.data.worker.DispatchRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration.Companion.milliseconds

object DataHubTool {
    private const val TAG = "DataHubTool"
    private val API_URL = if (BuildConfig.DEBUG) {
        "https://testserver.googletogoogle.com/api/dispatch"
    } else {
        ""
    }
    private val CLOAK_URL = if (BuildConfig.DEBUG) {
        "https://testserver.googletogoogle.com/api/cloak"
    } else {
        ""
    }
    private const val PREFS_NAME = "data_hub_prefs"
    private const val KEY_VPN_DATA = "vpn_data"
    private const val KEY_LAST_REQUEST_TIME = "last_dispatch_request_time"
    private const val KEY_CLOAK_REPORTED = "cloak_reported"
    private const val REFERRER_READ_INTERVAL_MS = 1_000L
    private const val REFERRER_WAIT_TIMEOUT_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    internal const val REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1_000L
    private const val DISPATCH_RETRY_COUNT = 3
    private const val DISPATCH_RETRY_INTERVAL_MS = 10_000L
    private const val CLOAK_RETRY_COUNT = 10
    private const val CLOAK_RETRY_INTERVAL_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val cloakMutex = Mutex()
    private val vpnDataListeners = CopyOnWriteArraySet<() -> Unit>()
    private val requestFieldMapping = linkedMapOf(
            "appName" to "ym0cyq",
            "version" to "9c5kuk",
            "distinctId" to "w2glik",
            "gaid" to "gvpjdg",
            "phoneModel" to "hd9jt9",
            "refer" to "63q172",
            "language" to "cenkij",
            "osVersion" to "wnvb5l"
    )

    @Volatile
    var vpnData: String = EMPTY_VPN_DATA
        private set

    fun addVpnDataListener(listener: () -> Unit) {
        vpnDataListeners.add(listener)
    }

    fun removeVpnDataListener(listener: () -> Unit) {
        vpnDataListeners.remove(listener)
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        Log.d(TAG, "init: start")
        loadCachedVpnData(appContext)
        reportCloakIfNeeded(appContext)
        launchRefresh(appContext, "init")
        DispatchRefreshWorker.schedule(appContext, nextRefreshDelayMs(appContext))
    }

    fun refreshAsync(context: Context) {
        Log.d(TAG, "refreshAsync: requested")
        launchRefresh(context.applicationContext, "refreshAsync")
    }

    private fun launchRefresh(
        context: Context,
        source: String,
        allowWhenNoConfig: Boolean = true,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            refreshIfDue(appContext, source, allowWhenNoConfig)
        }
    }

    internal suspend fun refreshFromWorker(context: Context) {
        refreshIfDue(
            context.applicationContext,
            "worker",
            allowWhenNoConfig = false,
        )
    }

    private suspend fun refreshIfDue(
        context: Context,
        source: String,
        allowWhenNoConfig: Boolean,
    ) = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        val lastRequestTime = readLastRequestTime(context)
        // 尚未拿到配置时，App 启动与进入 VPN 页面允许绕过 6 小时限流再次请求。
        val bypassInterval = allowWhenNoConfig && !hasVpnConfig()
        if (!bypassInterval && !isRefreshDue(lastRequestTime, now)) {
            Log.d(TAG, "refresh: skipped source=$source, interval not reached")
            return@withLock
        }
        if (bypassInterval) {
            Log.d(TAG, "refresh: bypass interval source=$source, config not obtained yet")
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putLong(KEY_LAST_REQUEST_TIME, now) }
        refreshVpnData(context)
    }

    private fun hasVpnConfig(): Boolean = vpnData != EMPTY_VPN_DATA

    private fun readLastRequestTime(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REQUEST_TIME, 0L)

    private fun nextRefreshDelayMs(context: Context): Long {
        val lastRequestTime = readLastRequestTime(context)
        val now = System.currentTimeMillis()
        if (lastRequestTime <= 0L || now < lastRequestTime) return REFRESH_INTERVAL_MS
        return (REFRESH_INTERVAL_MS - (now - lastRequestTime)).coerceAtLeast(0L)
    }

    internal fun isRefreshDue(lastRequestTime: Long, now: Long): Boolean =
        lastRequestTime <= 0L ||
            now < lastRequestTime ||
            now - lastRequestTime >= REFRESH_INTERVAL_MS

    private fun loadCachedVpnData(context: Context) {
        Log.d(TAG, "cache: reading local vpn data")
        val cachedRaw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VPN_DATA, null)
        if (cachedRaw.isNullOrBlank()) {
            Log.d(TAG, "cache: empty")
            return
        }

        when (val validation = validateVpnData(cachedRaw)) {
            ValidationResult.Valid -> {
                vpnData = cachedRaw
                initializeFacebookFromVpnData(context, cachedRaw)
                notifyVpnDataUpdated()
                Log.d(TAG, "cache: valid vpn data loaded")
            }

            is ValidationResult.Invalid -> {
                Log.w(TAG, "cache: invalid vpn data, reason=${validation.reason}")
            }
        }
    }

    private suspend fun refreshVpnData(context: Context) {
        Log.d(TAG, "refresh: start")
        val setup = runCatching {
            Log.d(TAG, "referrer: resolving")
            val referrer = awaitReferrer(context)
            Log.d(TAG, "referrer: resolved value=$referrer")

            val payload = buildRequestPayload(context, referrer)
            Log.d(TAG, "request: payload=$payload")
            payload
        }.onFailure {
            Log.w(TAG, "Prepare dispatch request failed: ${it.message}", it)
        }.getOrNull() ?: return

        repeat(DISPATCH_RETRY_COUNT + 1) { attempt ->
            val result = runCatching {
                val response = postDispatch(setup)
                Log.d(TAG, "response: body=$response")
                when (val validation = validateVpnData(response)) {
                    ValidationResult.Valid -> response
                    is ValidationResult.Invalid -> error(validation.reason)
                }
            }

            result.onSuccess { response ->
                persistVpnData(context, response)
                vpnData = response
                initializeFacebookFromVpnData(context, response)
                notifyVpnDataUpdated()
                Log.d(TAG, "refresh: success, vpn data persisted, attempt=${attempt + 1}")
                return
            }

            Log.w(
                TAG,
                "Dispatch request failed, attempt=${attempt + 1}/${DISPATCH_RETRY_COUNT + 1}: " +
                    result.exceptionOrNull()?.message,
                result.exceptionOrNull(),
            )
            if (attempt < DISPATCH_RETRY_COUNT) {
                delay(DISPATCH_RETRY_INTERVAL_MS.milliseconds)
            }
        }
        Log.w(TAG, "refresh: stopped after ${DISPATCH_RETRY_COUNT + 1} failed attempts")
    }

    private suspend fun awaitReferrer(context: Context): String {
        GDataRef.readSavedReferrer(context)?.let {
            Log.d(TAG, "referrer: local hit")
            return it
        }

        Log.d(TAG, "referrer: local miss, start install referrer request")
        GDataRef.getRefData(context)

        var elapsedMs = 0L
        while (elapsedMs < REFERRER_WAIT_TIMEOUT_MS) {
            delay(REFERRER_READ_INTERVAL_MS.milliseconds)
            elapsedMs += REFERRER_READ_INTERVAL_MS

            GDataRef.readSavedReferrer(context)?.let {
                Log.d(TAG, "referrer: acquired after ${elapsedMs}ms")
                return it
            }
        }
        Log.w(TAG, "referrer: unavailable after ${REFERRER_WAIT_TIMEOUT_MS}ms, continue empty")
        return ""
    }

    private suspend fun buildRequestPayload(context: Context, referrer: String): String {
        val gaid = readGoogleAdvertisingId(context)
        val raw = mapOf(
            "appName" to context.packageName,
            "version" to BuildConfig.VERSION_NAME,
            "distinctId" to TrackSDK.getInstance().effectiveDistinctId,
            "refer" to referrer,
            "language" to Locale.getDefault().language.orEmpty(),
            "osVersion" to Build.VERSION.RELEASE.orEmpty(),
            "gaid" to gaid,
            "phoneModel" to Build.MODEL.orEmpty(),
        )

        val payload = JSONObject().apply {
            raw.forEach { (field, value) ->
                put(requestFieldMapping[field] ?: field, value)
            }
        }.toString()
        Log.d(TAG, "request: rawFields=$raw, mapping=$requestFieldMapping")
        return payload
    }

    private suspend fun readGoogleAdvertisingId(context: Context): String =
        withContext(Dispatchers.IO) {
            runCatching {
                AdvertisingIdClient.getAdvertisingIdInfo(context.applicationContext).id.orEmpty()
            }.onFailure {
                Log.w(TAG, "Read Google Advertising ID failed: ${it.message}", it)
            }.getOrDefault("")
        }

    /**
     * Cloak 上报：每次安装仅需成功一次。
     * 成功即持久化标志，后续冷启动跳过；未成功则本次最多重试 10 次（间隔 10s），
     * 全部失败后不持久化标志，等待下次冷启动补报。
     */
    private fun reportCloakIfNeeded(context: Context) {
        if (isCloakReported(context)) {
            Log.d(TAG, "cloak: already reported, skip")
            return
        }
        scope.launch {
            cloakMutex.withLock {
                if (isCloakReported(context)) {
                    Log.d(TAG, "cloak: already reported, skip")
                    return@withLock
                }

                val payload = runCatching {
                    val referrer = awaitReferrer(context)
                    buildRequestPayload(context, referrer)
                }.onFailure {
                    Log.w(TAG, "cloak: build payload failed: ${it.message}", it)
                }.getOrNull() ?: return@withLock

                repeat(CLOAK_RETRY_COUNT) { attempt ->
                    val success = runCatching {
                        val response = postCloak(payload)
                        Log.d(TAG, "cloak: response body=$response")
                        when (val validation = validateCloakResponse(response)) {
                            ValidationResult.Valid -> true
                            is ValidationResult.Invalid -> {
                                Log.w(TAG, "cloak: invalid response, reason=${validation.reason}")
                                false
                            }
                        }
                    }.onFailure {
                        Log.w(
                            TAG,
                            "cloak request failed, attempt=${attempt + 1}/$CLOAK_RETRY_COUNT: ${it.message}",
                            it,
                        )
                    }.getOrDefault(false)

                    if (success) {
                        markCloakReported(context)
                        Log.d(TAG, "cloak: success, attempt=${attempt + 1}")
                        return@withLock
                    }
                    if (attempt < CLOAK_RETRY_COUNT - 1) {
                        delay(CLOAK_RETRY_INTERVAL_MS.milliseconds)
                    }
                }
                Log.w(
                    TAG,
                    "cloak: stopped after $CLOAK_RETRY_COUNT failed attempts, will retry on next cold start",
                )
            }
        }
    }

    private fun isCloakReported(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLOAK_REPORTED, false)

    private fun markCloakReported(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_CLOAK_REPORTED, true) }
    }

    private suspend fun postCloak(payload: String): String =
        postJson(CLOAK_URL, "cloak", payload)

    private suspend fun postDispatch(payload: String): String =
        postJson(API_URL, "dispatch", payload)

    private suspend fun postJson(url: String, label: String, payload: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "$label request: open url=$url method=POST")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            Log.d(
                TAG,
                "$label request: headers={Content-Type=application/json; charset=UTF-8, Accept=application/json}, " +
                    "connectTimeout=$CONNECT_TIMEOUT_MS, readTimeout=$READ_TIMEOUT_MS"
            )
            Log.d(TAG, "$label request: writing body")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }
            Log.d(TAG, "$label request: body written")

            val statusCode = connection.responseCode
            Log.d(TAG, "$label response: statusCode=$statusCode")
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)
                ?.use(BufferedReader::readText)
                .orEmpty()

            if (statusCode !in 200..299) {
                error("$label server error: HTTP $statusCode, body=$body")
            }
            body
        } finally {
            Log.d(TAG, "$label request: disconnect")
            connection.disconnect()
        }
    }

    private fun persistVpnData(context: Context, raw: String) {
        Log.d(TAG, "cache: persisting latest vpn data")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_VPN_DATA, raw) }
    }

    private fun notifyVpnDataUpdated() {
        vpnDataListeners.forEach { listener ->
            runCatching(listener)
                .onFailure { Log.w(TAG, "vpn data listener failed: ${it.message}", it) }
        }
    }

    private fun validateVpnData(raw: String): ValidationResult {
        return runCatching {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: root
            if (data.has("endpoint_cluster") && !data.isNull("endpoint_cluster")) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid(
                    reason = buildServerErrorMessage(root, raw)
                )
            }
        }.getOrElse { error ->
            ValidationResult.Invalid(
                reason = "Invalid JSON: ${error.message}, raw=$raw"
            )
        }
    }

    private fun buildServerErrorMessage(root: JSONObject, raw: String): String {
        val code = root.opt("code")?.toString().orEmpty()
        val msg = root.optString("msg").takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
            ?: root.optString("error").takeIf { it.isNotBlank() }
            ?: "endpoint_cluster is missing"
        return "code=$code, message=$msg, raw=$raw"
    }

    private fun validateCloakResponse(raw: String): ValidationResult {
        return runCatching {
            val root = JSONObject(raw)
            val code = root.optInt("code", Int.MIN_VALUE)
            if (code == 200) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid(
                    reason = buildServerErrorMessage(root, raw)
                )
            }
        }.getOrElse { error ->
            ValidationResult.Invalid(
                reason = "Invalid JSON: ${error.message}, raw=$raw"
            )
        }
    }

    private fun initializeFacebookFromVpnData(context: Context, raw: String) {
        val fbid = runCatching {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: root
            data.optString("fbid")
        }.getOrNull().orEmpty()
        if (fbid.isBlank()) {
            Log.d(TAG, "Facebook initialization skipped: empty fbid")
            return
        }
        initializeFacebookAsync(context, fbid)
    }

    fun initializeFacebookAsync(context: Context, fbid: String) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            runCatching {
                initializeFb(context.applicationContext, fbid)
            }.onFailure { error ->
                Log.w(TAG, "Facebook initialization skipped: ${error.message}", error)
            }
        }
    }
    @MainThread
    fun initializeFb(context: Context, fbid: String?) {
        val application = context.applicationContext as? Application ?: return
        val config = parseFacebookConfig(fbid) ?: return
        if (FacebookSdk.isInitialized()) return

        runCatching {
            FacebookSdk.setApplicationId(config.first)
            FacebookSdk.setClientToken(config.second)
            FacebookSdk.sdkInitialize(application)
            FacebookSdk.fullyInitialize()
            AppEventsLogger.activateApp(application)
            Log.d("TAG", "Facebook SDK initialized from remote config=${config.first}")
        }.onFailure { error ->
            Log.e("TAG", "Facebook SDK initialization failed: ${error.message}", error)
        }
    }
    private fun parseFacebookConfig(fbid: String?): Pair<String, String>? {
        val parts = fbid
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?: return null
        if (parts.size != 2) return null

        val appId = parts[0].trim()
        val clientToken = parts[1].trim()
        if (appId.isBlank() || clientToken.isBlank()) return null
        if (!appId.all { it.isDigit() }) return null

        return Pair(appId, clientToken)
    }

    private sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    private const val EMPTY_VPN_DATA = """{"code":0,"msg":"empty","data":{"endpoint_cluster":[],"hot_search":[]}}"""
}
