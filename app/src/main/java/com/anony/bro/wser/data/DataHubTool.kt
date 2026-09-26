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
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.ref.GDataRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        "https://api.realtictools.cc/api/dispatch"
    }
    private val CLOAK_URL = if (BuildConfig.DEBUG) {
        "https://testserver.googletogoogle.com/api/cloak"
    } else {
        "https://api.realtictools.cc/api/cloak"
    }
    private const val PREFS_NAME = "data_hub_prefs"
    private const val KEY_VPN_DATA = "vpn_data"
    private const val REFERRER_READ_INTERVAL_MS = 1_000L
    private const val REFERRER_RETRY_INTERVAL_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val PERIODIC_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Any()
    private val vpnDataListeners = CopyOnWriteArraySet<() -> Unit>()
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private val requestFieldMapping = linkedMapOf(
        "appName" to "cka5mb",
        "version" to "4w9m5t",
        "distinctId" to "uf1qbb",
        "refer" to "5jed9k",
        "language" to "y96k0h",
        "osVersion" to "yd7yhk",
        "gaid" to "cwecbt",
        "phoneModel" to "mhjytr",
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
        launchRefresh(appContext, "init", shouldRequestCloak = true)
        startPeriodicRefresh(appContext)
    }

    fun refreshAsync(context: Context) {
        Log.d(TAG, "refreshAsync: requested")
        launchRefresh(context.applicationContext, "refreshAsync", shouldRequestCloak = false)
    }

    /** 进程存活期间每 6 小时刷新一次 dispatch 配置；进程结束后自然停止。 */
    private fun startPeriodicRefresh(context: Context) {
        synchronized(refreshLock) {
            if (periodicRefreshJob?.isActive == true) return
            periodicRefreshJob = scope.launch {
                while (true) {
                    delay(PERIODIC_REFRESH_INTERVAL_MS.milliseconds)
                    Log.d(TAG, "periodic refresh: tick")
                    runCatching {
                        launchRefresh(context, "periodic", shouldRequestCloak = false)
                    }.onFailure {
                        Log.w(TAG, "periodic refresh schedule failed: ${it.message}", it)
                    }
                }
            }
        }
    }

    private fun launchRefresh(context: Context, source: String, shouldRequestCloak: Boolean) {
        val appContext = context.applicationContext
        synchronized(refreshLock) {
            if (refreshJob?.isActive == true) {
                Log.d(TAG, "refresh: already running, skip source=$source")
                return
            }

            refreshJob = scope.launch {
                try {
                    refreshVpnData(appContext, shouldRequestCloak)
                } finally {
                    synchronized(refreshLock) {
                        if (refreshJob == coroutineContext[Job]) {
                            refreshJob = null
                        }
                    }
                }
            }
        }
    }

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

    private suspend fun refreshVpnData(context: Context, shouldRequestCloak: Boolean) {
        Log.d(TAG, "refresh: start")
        runCatching {
            Log.d(TAG, "referrer: resolving")
            val referrer = awaitReferrer(context)
            Log.d(TAG, "referrer: resolved value=$referrer")

            val payload = buildRequestPayload(context, referrer)
            Log.d(TAG, "request: payload=$payload")

            if (shouldRequestCloak) {
                requestCloak(payload)
            }

            val response = postDispatch(payload)
            Log.d(TAG, "response: body=$response")

            when (val validation = validateVpnData(response)) {
                ValidationResult.Valid -> {
                    persistVpnData(context, response)
                    vpnData = response
                    initializeFacebookFromVpnData(context, response)
                    notifyVpnDataUpdated()
                    Log.d(TAG, "refresh: success, vpn data persisted")
                }

                is ValidationResult.Invalid -> {
                    Log.w(TAG, "refresh: invalid response, reason=${validation.reason}")
                    return
                }
            }
        }.onFailure {
            Log.w(TAG, "Refresh vpn data failed: ${it.message}", it)
        }
    }

    private suspend fun awaitReferrer(context: Context): String {
        GDataRef.readSavedReferrer(context)?.let {
            Log.d(TAG, "referrer: local hit")
            return it
        }

        Log.d(TAG, "referrer: local miss, start install referrer request")
        GDataRef.getRefData(context)

        var elapsedSinceRequestMs = 0L
        var pollCount = 0
        var requestCount = 1
        while (true) {
            delay(REFERRER_READ_INTERVAL_MS.milliseconds)
            elapsedSinceRequestMs += REFERRER_READ_INTERVAL_MS
            pollCount += 1
            Log.d(TAG, "referrer: polling count=$pollCount")

            GDataRef.readSavedReferrer(context)?.let {
                Log.d(
                    TAG,
                    "referrer: acquired after polling count=$pollCount, requestCount=$requestCount"
                )
                return it
            }

            if (elapsedSinceRequestMs >= REFERRER_RETRY_INTERVAL_MS) {
                requestCount += 1
                elapsedSinceRequestMs = 0L
                Log.d(TAG, "referrer: retry getRefData, requestCount=$requestCount")
                GDataRef.getRefData(context)
            }
        }
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

    private suspend fun requestCloak(payload: String) {
        runCatching {
            val response = postCloak(payload)
            Log.d(TAG, "cloak: response body=$response")
            when (val validation = validateCloakResponse(response)) {
                ValidationResult.Valid -> Log.d(TAG, "cloak: success")
                is ValidationResult.Invalid -> {
                    Log.w(TAG, "cloak: invalid response, reason=${validation.reason}")
                }
            }
        }.onFailure {
            Log.w(TAG, "cloak request failed: ${it.message}", it)
        }
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
