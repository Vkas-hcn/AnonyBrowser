package com.anony.bro.wser.vpn

import android.content.Context
import androidx.core.content.edit
import com.anony.bro.wser.data.NetTool
import com.anony.bro.wser.data.recommendations.RegionResolver
import com.anony.bro.wser.data.recommendations.WebsiteCategory
import com.anony.bro.wser.data.recommendations.WebsiteRecommendationRepository
import com.anony.bro.wser.hellohello.NotificationCopyData
import com.anony.bro.wser.hellohello.NotificationCopyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VpnNewsContent(
    val category: WebsiteCategory,
    val title: String,
    val url: String,
)

object VpnNewsContentRepository {
    private const val PREFS_NAME = "vpn_news_category_rotation"
    private const val KEY_NEXT_CATEGORY_INDEX = "next_category_index"
    private const val COPY_CACHE_NAMESPACE = "vpn_bar"
    private val selectionLock = Any()

    suspend fun nextContent(context: Context): VpnNewsContent? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val region = RegionResolver.resolve(appContext)
        val city = NetTool.getCachedCity(appContext) ?: region.displayName
        val recommendations = WebsiteRecommendationRepository.allRecommendations(appContext, region)
        val (category, website) = synchronized(selectionLock) {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val selectedCategory = VpnNewsCategoryRotation.next(
                available = recommendations.filterValues { it.isNotEmpty() }.keys,
                startIndex = prefs.getInt(KEY_NEXT_CATEGORY_INDEX, 0),
            ) ?: return@withContext null
            val selectedWebsite = recommendations.getValue(selectedCategory).random()
            prefs.edit {
                putInt(KEY_NEXT_CATEGORY_INDEX, VpnNewsCategoryRotation.nextIndex(selectedCategory))
            }
            selectedCategory to selectedWebsite
        }
        val copy = NotificationCopyGenerator.generate(
            category = category,
            data = NotificationCopyData(
                topic = website.name,
                country = region.displayName,
                city = city,
            ),
            cacheNamespace = COPY_CACHE_NAMESPACE,
        )
        VpnNewsContent(category = category, title = copy.newsTitle, url = website.url)
    }

    fun resetCopyCache() {
        NotificationCopyGenerator.reset(COPY_CACHE_NAMESPACE)
    }
}
