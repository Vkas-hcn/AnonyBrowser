package com.anony.bro.wser.hellohello

import android.util.Log
import com.anony.bro.wser.BuildConfig
import com.anony.bro.wser.hellohello.fcm.FcmNotificationDecision
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM 入口（Topic [FCM_TOPIC]）。
 * 展示与判断见 [FcmNotificationDecision]，与系统通知 / 常驻栏无关。
 *
 * **自定义样式前提**：服务端必须发 **data-only**（不要带 `notification` 节点）。
 * Console「发送测试消息」若带 Notification 标题，后台由系统画默认样式，不会进 [onMessageReceived]。
 */
class MessageRecv : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM 令牌已刷新：${describeToken(token)}")
        subscribeTopic(source = "令牌刷新")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val dataKeys = message.data.keys.sorted()
        val hasNotification = message.notification != null
        Log.i(
            TAG,
            "FCM 消息到达：消息ID=${message.messageId.orEmpty()}, " +
                "发送方=${message.from.orEmpty()}, 数据键=$dataKeys, " +
                "含通知节点=${if (hasNotification) "是" else "否"}, 优先级=${message.priority}",
        )
        val data = LinkedHashMap<String, String>()
        message.data.forEach { (key, value) -> data[key] = value }
        message.notification?.let { notification ->
            if (!data.containsKey("title")) notification.title?.let { data["title"] = it }
            if (!data.containsKey("body")) notification.body?.let { data["body"] = it }
        }
        if (data.isEmpty()) {
            Log.w(TAG, "丢弃 FCM：载荷为空")
            return
        }
        Log.i(
            TAG,
            "FCM 载荷已解析：字段数=${data.size}, 键名=${data.keys.sorted()}, " +
                "含title=${if (data.containsKey("title")) "是" else "否"}, " +
                "含body=${if (data.containsKey("body")) "是" else "否"}, " +
                "含cta=${if (data.containsKey("cta")) "是" else "否"}, " +
                "含link=${if (data.containsKey("link")) "是" else "否"}, " +
                "类型=${data["type"].orEmpty()}, 声音模式=${data["soundMode"].orEmpty()}",
        )
        FcmNotificationDecision.onMessage(data)
    }

    companion object {
        const val FCM_TOPIC = "anony"
        private const val TAG = "FcmNews"

        fun subscribeTopic(source: String = "启动") {
            Log.i(TAG, "FCM 主题订阅开始：主题=$FCM_TOPIC, 来源=$source")
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC)
                    .addOnSuccessListener {
                        Log.i(TAG, "FCM 主题订阅成功：主题=$FCM_TOPIC, 来源=$source")
                        logCurrentToken(source)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "FCM 主题订阅失败：主题=$FCM_TOPIC, 来源=$source", error)
                    }
            }.onFailure { error ->
                Log.w(TAG, "FCM 主题订阅发起失败：主题=$FCM_TOPIC, 来源=$source", error)
            }
        }

        private fun logCurrentToken(source: String) {
            runCatching {
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        Log.i(TAG, "FCM 令牌就绪：来源=$source, ${describeToken(token)}")
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "FCM 令牌获取失败：来源=$source", error)
                    }
            }.onFailure { error ->
                Log.w(TAG, "FCM 令牌获取发起失败：来源=$source", error)
            }
        }

        private fun describeToken(token: String): String {
            if (token.isEmpty()) return "长度=0"
            return if (BuildConfig.DEBUG) {
                "长度=${token.length}, 令牌=$token"
            } else {
                "长度=${token.length}, 末尾=...${token.takeLast(6)}"
            }
        }
    }
}
