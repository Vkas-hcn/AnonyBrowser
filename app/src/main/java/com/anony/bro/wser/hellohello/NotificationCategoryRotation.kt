package com.anony.bro.wser.hellohello

import com.anony.bro.wser.data.recommendations.WebsiteCategory

object NotificationCategoryRotation {
    fun next(available: Set<WebsiteCategory>, startIndex: Int): WebsiteCategory? {
        val categories = WebsiteCategory.entries
        repeat(categories.size) { offset ->
            val category = categories[(startIndex + offset).floorMod(categories.size)]
            if (category in available) return category
        }
        return null
    }

    fun nextIndex(category: WebsiteCategory): Int =
        (category.ordinal + 1) % WebsiteCategory.entries.size

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
