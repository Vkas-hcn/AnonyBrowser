package com.anony.bro.wser.hellohello.fcm

import android.util.Log
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.hellohello.HintUtil
import com.anony.bro.wser.hellohello.NoticeConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

internal data class FcmNewsContent(
    val id: String,
    val title: String,
    val body: String,
    val cta: String,
    val link: String,
    val type: String,
)

/**
 * FCM Topic 专属触发与判断（与系统通知 / 常驻栏无关，勿混用）。
 *
 * 延迟：收到后随机 1–2s。
 * 门禁：运行时已初始化、非 KR+三星、非应用前台、通知权限。
 * 不依赖运营 Config 总开关、策略就绪或允许时段。
 */
internal object FcmNotificationDecision {
    private const val TAG = "FcmNews"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var runtimeReady = false

    @Volatile
    private var pendingPayload: Map<String, String>? = null

    fun onMessage(data: Map<String, String>) {
        Log.i(
            TAG,
            "FCM 消息已接收：来源=主题, 字段数=${data.size}, 键名=${data.keys.sorted()}",
        )
        if (!runtimeReady) {
            pendingPayload = HashMap(data)
            Log.i(TAG, "FCM 已暂存：原因=通知运行时未初始化, 字段数=${data.size}")
            return
        }
        scope.launch { process(data, source = "消息到达") }
    }

    fun onRuntimeReady() {
        runtimeReady = true
        val data = pendingPayload ?: run {
            Log.d(TAG, "FCM 运行时就绪：无暂存消息")
            return
        }
        pendingPayload = null
        Log.i(TAG, "等待中的 FCM 开始处理：原因=运行时就绪, 字段数=${data.size}")
        scope.launch { process(data, source = "运行时就绪") }
    }

    private suspend fun process(data: Map<String, String>, source: String) {
        val delayMs = Random.nextLong(DELAY_MIN_MS, DELAY_MAX_MS + 1)
        Log.i(TAG, "FCM 消息开始处理：来源=$source, 延迟=${delayMs}毫秒")
        delay(delayMs)

        val content = contentFromPayload(data)
        val soundMode = soundModeFromPayload(data)
        Log.i(
            TAG,
            "FCM 展示判断：来源=$source, 内容ID=${content.id}, 类型=${content.type}, " +
                "声音模式=$soundMode, 含链接=${if (content.link.isNotBlank()) "是" else "否"}",
        )

        val gate = evaluateGateReason()
        if (gate != null) {
            Log.w(
                TAG,
                "FCM 未展示：原因=$gate, 内容ID=${content.id}, 来源=$source, " +
                    "应用在前台=${if (GateBrowserApplication.get().isAppForeground()) "是" else "否"}",
            )
            return
        }

        val shown = FcmNotificationPresenter.show(content = content, soundMode = soundMode)
        if (!shown) {
            Log.w(TAG, "FCM 未展示：原因=通知提交失败, 内容ID=${content.id}, 来源=$source")
            return
        }
        Log.i(TAG, "FCM 展示成功：内容ID=${content.id}, 来源=$source, 声音模式=$soundMode")
    }

    private suspend fun evaluateGateReason(): String? =
        FcmNotificationGate.blockReason(
            runtimeInitialized = runtimeReady,
            isForeground = GateBrowserApplication.get().isAppForeground(),
            isKrSamsung = NoticeConfigStore.isSamsung() && NoticeConfigStore.isKoreanCountry(),
            hasNotificationPermission = HintUtil.coHavePermission(),
        )

    private fun contentFromPayload(data: Map<String, String>): FcmNewsContent =
        FcmNewsContent(
            id = (data["type"] ?: "").take(40).ifBlank { "topic" },
            title = data["title"].orEmpty(),
            body = data["body"].orEmpty(),
            cta = data["cta"].orEmpty(),
            link = data["link"].orEmpty(),
            type = data["type"].orEmpty(),
        )

    private fun soundModeFromPayload(data: Map<String, String>): Int =
        data["soundMode"]?.toIntOrNull()?.takeIf { it == 0 || it == 1 } ?: 0

    private const val DELAY_MIN_MS = 1_000L
    private const val DELAY_MAX_MS = 2_000L
}
