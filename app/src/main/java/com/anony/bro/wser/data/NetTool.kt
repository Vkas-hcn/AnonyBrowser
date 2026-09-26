package com.anony.bro.wser.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object NetTool {
    private const val PREFS_NAME = "web_vpn_ip_cache"
    private const val KEY_COUNTRY_CODE = "web_cached_country_code"
    private const val KEY_CITY = "web_cached_city"
    private const val KEY_LATITUDE = "web_cached_latitude"
    private const val KEY_LONGITUDE = "web_cached_longitude"

    suspend fun fetchCountryCode(context: Context): String? =
        fetchLocation(context)?.countryCode ?: getCachedCountryCode(context)

    suspend fun fetchLocation(context: Context): IpLocation? = withContext(Dispatchers.IO) {
        val infoIp = async { requestLocation("https://api.infoip.io/", ::parseInfoIp) }
        val ipInfo = async { requestLocation("https://ipinfo.io/json", ::parseIpInfo) }
        val first = select<IpLocation?> {
            infoIp.onAwait { it }
            ipInfo.onAwait { it }
        }
        val location = first ?: infoIp.await() ?: ipInfo.await()
        location?.also { saveLocation(context, it) } ?: getCachedLocation(context)
    }

    private fun requestLocation(
        url: String,
        parser: (JSONObject) -> IpLocation?,
    ): IpLocation? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 3_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            parser(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun parseInfoIp(json: JSONObject): IpLocation? {
        val country = json.optString("country_short").normalizedCountry() ?: return null
        return IpLocation(
            countryCode = country,
            city = json.optString("city").trim(),
            latitude = json.optDouble("latitude", Double.NaN),
            longitude = json.optDouble("longitude", Double.NaN),
        ).takeIf { it.hasCoordinates }
    }

    private fun parseIpInfo(json: JSONObject): IpLocation? {
        val country = json.optString("country").normalizedCountry() ?: return null
        val coordinates = json.optString("loc").split(',').mapNotNull(String::toDoubleOrNull)
        return IpLocation(
            countryCode = country,
            city = json.optString("city").trim(),
            latitude = coordinates.getOrNull(0) ?: Double.NaN,
            longitude = coordinates.getOrNull(1) ?: Double.NaN,
        ).takeIf { it.hasCoordinates }
    }

    private fun saveLocation(context: Context, location: IpLocation) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_COUNTRY_CODE, location.countryCode)
            putString(KEY_CITY, location.city)
            putString(KEY_LATITUDE, location.latitude.toString())
            putString(KEY_LONGITUDE, location.longitude.toString())
        }
    }

    private fun getCachedLocation(context: Context): IpLocation? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val country = prefs.getString(KEY_COUNTRY_CODE, null).normalizedCountry() ?: return null
        return IpLocation(
            countryCode = country,
            city = prefs.getString(KEY_CITY, null).orEmpty(),
            latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: Double.NaN,
            longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: Double.NaN,
        ).takeIf { it.hasCoordinates }
    }

    fun getCachedCountryCode(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COUNTRY_CODE, null)
            .normalizedCountry()

    fun getCachedCity(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CITY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun String?.normalizedCountry(): String? =
        this?.trim()?.uppercase(Locale.US)?.takeIf { it.matches(Regex("[A-Z]{2}")) }

    data class IpLocation(
        val countryCode: String,
        val city: String,
        val latitude: Double,
        val longitude: Double,
    ) {
        val hasCoordinates: Boolean
            get() = latitude in -90.0..90.0 && longitude in -180.0..180.0

        val displayName: String
            get() = city.ifBlank { countryCode }
    }
}
