package com.anony.bro.wser.data.ref

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

object GDataRef {
    private const val TAG = "Referrer"
    private const val PREFS_NAME = "data_hub_prefs"
    private const val KEY_REFERRER = "install_referrer"

    fun getRefData(context: Context) {
        try {
            val appContext = context.applicationContext
            val client = InstallReferrerClient.newBuilder(appContext).build()
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    runCatching {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            val referrer = client.installReferrer.installReferrer
                            if (referrer.isNotBlank()) {
                                saveReferrer(appContext, referrer)
                                Log.d(TAG, "Referrer: $referrer")
                            }
                        }
                    }.onFailure {
                        Log.w(TAG, "Read referrer failed: ${it.message}", it)
                    }
                    runCatching { client.endConnection() }
                        .onFailure { Log.w(TAG, "End connection failed: ${it.message}") }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    runCatching { client.endConnection() }
                        .onFailure { Log.w(TAG, "End connection failed: ${it.message}") }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Start referrer request failed: ${e.message}", e)
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
