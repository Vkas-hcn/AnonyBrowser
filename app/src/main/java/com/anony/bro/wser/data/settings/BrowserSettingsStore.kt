package com.anony.bro.wser.data.settings

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 浏览器设置与首页快捷方式相关的本地偏好。
 */
object BrowserSettingsStore {

    enum class AddressBarPosition {
        BOTTOM,
        CENTER,
        TOP,
        ;

        companion object {
            fun fromStorage(raw: String?): AddressBarPosition =
                entries.firstOrNull { it.name == raw } ?: CENTER
        }
    }

    private const val PREFS_SETTINGS = "browser_settings"
    private const val PREFS_HOME = "browser_home"
    private const val KEY_ADDRESS_BAR_POSITION = "address_bar_position"
    private const val KEY_SEAMLESS = "seamless_enabled"
    private const val KEY_SHORTCUTS = "shortcuts"

    fun isSeamlessEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEAMLESS, false)

    fun setSeamlessEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SEAMLESS, enabled) }
    }

    fun getAddressBarPosition(context: Context): AddressBarPosition =
        AddressBarPosition.fromStorage(
            context.applicationContext
                .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getString(KEY_ADDRESS_BAR_POSITION, AddressBarPosition.CENTER.name),
        )

    fun setAddressBarPosition(context: Context, position: AddressBarPosition) {
        context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_ADDRESS_BAR_POSITION, position.name)
            }
    }

    fun resetAppearanceToDefault(context: Context) {
        setAddressBarPosition(context, AddressBarPosition.CENTER)
    }

    /**
     * 清除用户手动新增的快捷网址，恢复为默认快捷方式。
     */
    fun resetUserShortcuts(context: Context) {
        val appContext = context.applicationContext
        val defaults = defaultShortcuts()
        val array = JSONArray()
        defaults.forEach { shortcut ->
            array.put(
                JSONObject().apply {
                    put("id", shortcut.id)
                    put("name", shortcut.name)
                    put("url", shortcut.url)
                    put("iconResName", shortcut.iconResName)
                    put("faviconPath", "")
                },
            )
        }
        appContext.getSharedPreferences(PREFS_HOME, Context.MODE_PRIVATE).edit {
            putString(KEY_SHORTCUTS, array.toString())
        }
        File(appContext.filesDir, "shortcut_favicons").deleteRecursively()
    }

    private fun defaultShortcuts(): List<DefaultShortcut> = listOf(
        DefaultShortcut("default_youtube", "YouTube", "https://www.youtube.com", "ic_youtube"),
        DefaultShortcut("default_facebook", "Facebook", "https://www.facebook.com", "ic_facebook"),
        DefaultShortcut("default_amazon", "Amazon", "https://www.amazon.com", "ic_amazon"),
        DefaultShortcut("default_tiktok", "Tiktok", "https://www.tiktok.com", "ic_tiktok"),
        DefaultShortcut("default_wikipedia", "Wikipedia", "https://www.wikipedia.org", "ic_wikipedia"),
        DefaultShortcut("default_x", "X", "https://x.com", "ic_x"),
    )

    private data class DefaultShortcut(
        val id: String,
        val name: String,
        val url: String,
        val iconResName: String,
    )
}
