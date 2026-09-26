package com.anony.bro.wser.hellohello

import android.content.Context
import androidx.core.content.edit
import com.anony.bro.wser.data.NetTool
import com.anony.bro.wser.data.recommendations.RegionResolver
import com.anony.bro.wser.data.recommendations.WebsiteCategory
import com.anony.bro.wser.data.recommendations.WebsiteRecommendationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationContent(
    val id: String,
    val category: WebsiteCategory,
    val title: String,
    val action: String,
    val summary: String,
    val url: String,
)

object NotificationContentRepository {
    private const val PREFS_NAME = "notification_category_rotation"
    private const val KEY_NEXT_CATEGORY_INDEX = "next_category_index"
    private val rotationLock = Any()

    suspend fun randomContent(context: Context): NotificationContent? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val region = RegionResolver.resolve(appContext)
        val city = NetTool.getCachedCity(appContext) ?: region.displayName
        val recommendations = WebsiteRecommendationRepository.recommendations(appContext, region)
        val (category, website) = synchronized(rotationLock) {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val selectedCategory = NotificationCategoryRotation.next(
                available = recommendations.filterValues { it.isNotEmpty() }.keys,
                startIndex = prefs.getInt(KEY_NEXT_CATEGORY_INDEX, 0),
            ) ?: return@withContext null
            val selectedWebsite = recommendations.getValue(selectedCategory).random()
            prefs.edit { putInt(KEY_NEXT_CATEGORY_INDEX, NotificationCategoryRotation.nextIndex(selectedCategory)) }
            selectedCategory to selectedWebsite
        }
        run {
            val copy = NotificationCopyGenerator.generate(
                category = category,
                data = NotificationCopyData(
                    topic = website.name,
                    country = region.displayName,
                    city = city,
                ),
            )
            NotificationContent(
                id = website.id,
                category = category,
                title = copy.newsTitle,
                action = copy.newsAction,
                summary = website.description,
                url = website.url,
            )
        }
    }

    fun resetCopyCache() {
        NotificationCopyGenerator.reset()
    }
}
