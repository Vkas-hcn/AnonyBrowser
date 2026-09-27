package com.anony.bro.wser.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 留存回访埋点：用户首次安装后，在第 N 个自然日回访时各触发一次。
 *
 * - 里程碑：D1 / D2 / D3 / D5 / D7 / D14 / D30。
 * - 安装锚点取系统 [android.content.pm.PackageInfo.firstInstallTime]，天然抗进程杀死，无需额外记录。
 * - 「自然日」按设备本地时区计算安装日与当前日的日期差。
 * - 每个里程碑以 SharedPreferences 去重，保证仅触发一次。
 *
 * 复用 [UpDataTool.trackEvent] 统一上报至 BI、Firebase；全程 IO 子线程执行并捕获异常，
 * 不阻塞主线程、不影响任何业务逻辑。
 */
object RetentionTracking {
    private const val TAG = "RetentionTracking"

    /** 留存里程碑（自然日）。 */
    val MILESTONES = intArrayOf(1, 2, 3, 5, 7, 14, 30)

    private const val PREFS_NAME = "retention_tracking"
    private const val KEY_REPORTED_PREFIX = "reported_d"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun eventName(day: Int): String = "retention_d$day"

    /**
     * 计算本次回访命中的里程碑（纯函数，无副作用，便于测试）。
     * 仅当"距安装自然日差"精确等于某里程碑且未上报过时返回该里程碑。
     */
    internal fun dueMilestones(diffDays: Long, isReported: (Int) -> Boolean): List<Int> =
        MILESTONES.filter { it.toLong() == diffDays && !isReported(it) }

    /**
     * 触发一次回访检查：命中里程碑则上报并去重。
     * 建议在应用回到前台（含冷启动）时调用；本方法自身在 IO 线程执行，调用方无需切线程。
     */
    fun track(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val installDay = firstInstallEpochDay(appContext) ?: run {
                    Log.w(TAG, "retention: install day unavailable, skip")
                    return@runCatching
                }
                val today = LocalDate.now().toEpochDay()
                val diff = today - installDay
                if (diff <= 0) {
                    Log.d(TAG, "retention: diff=$diff, no milestone due")
                    return@runCatching
                }

                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val due = dueMilestones(diff) { day ->
                    prefs.getBoolean(KEY_REPORTED_PREFIX + day, false)
                }
                due.forEach { day ->
                    UpDataTool.trackEvent(eventName(day))
                    prefs.edit { putBoolean(KEY_REPORTED_PREFIX + day, true) }
                    Log.d(TAG, "retention: reported ${eventName(day)}, diffDays=$diff")
                }
            }.onFailure {
                Log.w(TAG, "retention track failed: ${it.message}", it)
            }
        }
    }

    /** 读取首次安装日期并转换为本地时区的 epochDay；失败返回 null。 */
    private fun firstInstallEpochDay(context: Context): Long? = runCatching {
        val firstInstallTime = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .firstInstallTime
        Instant.ofEpochMilli(firstInstallTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
    }.getOrNull()
}
