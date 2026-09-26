package com.anony.bro.wser.data.recommendations

import android.content.Context
import androidx.core.content.edit
import com.anony.bro.wser.data.NetTool
import java.util.Locale

data class RecommendationRegion(
    val countryCode: String?,
    val languageCode: String,
    val displayName: String,
)

object RegionResolver {
    private const val PREFS_NAME = "recommendation_region"
    private const val KEY_COUNTRY_OVERRIDE = "country_override"

    suspend fun resolve(context: Context): RecommendationRegion {
        val appContext = context.applicationContext
        val locale = Locale.getDefault()
        val country = selectedCountry(appContext)
            ?: NetTool.fetchCountryCode(appContext)
            ?: locale.country.takeIf { it.matches(Regex("[A-Z]{2}")) }
        return RecommendationRegion(
            countryCode = country,
            languageCode = locale.language.lowercase(Locale.US).takeIf { it.isNotBlank() } ?: "en",
            displayName = country?.let(::countryName) ?: "Global",
        )
    }

    fun setCountryOverride(context: Context, countryCode: String?) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            if (countryCode.isNullOrBlank()) remove(KEY_COUNTRY_OVERRIDE)
            else putString(KEY_COUNTRY_OVERRIDE, countryCode.uppercase(Locale.US))
        }
    }

    private fun selectedCountry(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COUNTRY_OVERRIDE, null)
            ?.uppercase(Locale.US)
            ?.takeIf { it.matches(Regex("[A-Z]{2}")) }

    private fun countryName(countryCode: String): String =
        Locale("", countryCode).displayCountry.takeIf { it.isNotBlank() } ?: countryCode
}
