package com.anony.bro.wser.data.ref

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

object GDataRef {
    private const val TAG = "Referrer"
    private const val PREFS_NAME = "data_hub_prefs"
    private const val KEY_REFERRER = "install_referrer"
    private val fetchMutex = Mutex()

    suspend fun getRefData(context: Context): String? {
        readSavedReferrer(context)?.let { return it }
        return fetchMutex.withLock {
            readSavedReferrer(context) ?: requestInstallReferrer(context)
        }
    }

    private suspend fun requestInstallReferrer(context: Context): String? {
        val appContext = context.applicationContext
        val client = try {
            InstallReferrerClient.newBuilder(appContext).build()
        } catch (e: Exception) {
            Log.w(TAG, "Start referrer request failed: ${e.message}", e)
            return null
        }
        return suspendCancellableCoroutine { cont ->
            val once = AtomicBoolean(false)
            fun deliver(value: String?) {
                if (once.compareAndSet(false, true)) cont.resume(value) {}
            }
            cont.invokeOnCancellation {
                runCatching { client.endConnection() }
                    .onFailure { Log.w(TAG, "End connection failed: ${it.message}") }
            }
            try {
                client.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        val referrer = runCatching {
                            if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
                                Log.w(TAG, "Referrer unavailable, responseCode=$responseCode")
                                null
                            } else {
                                client.installReferrer.installReferrer
                                    ?.takeIf { it.isNotBlank() }
                                    ?.also {
                                        saveReferrer(appContext, it)
                                        Log.d(TAG, "Referrer: $it")
                                    }
                            }
                        }.onFailure {
                            Log.w(TAG, "Read referrer failed: ${it.message}", it)
                        }.getOrNull()
                        deliver(referrer)
                        runCatching { client.endConnection() }
                            .onFailure { Log.w(TAG, "End connection failed: ${it.message}") }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        deliver(null)
                        runCatching { client.endConnection() }
                            .onFailure { Log.w(TAG, "End connection failed: ${it.message}") }
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Start referrer request failed: ${e.message}", e)
                deliver(null)
            }
        }
    }

    fun readSavedReferrer(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REFERRER, null)
            ?.takeIf { it.isNotBlank() }

    private fun saveReferrer(context: Context, referrer: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_REFERRER, referrer) }
    }
}
