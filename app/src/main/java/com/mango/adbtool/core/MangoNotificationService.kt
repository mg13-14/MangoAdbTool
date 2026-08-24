package com.mango.adbtool.core
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 通知监听服务：监听系统配对码通知，自动抓取配对码填入弹窗。
 * 需要用户在系统设置中授予「通知使用权」（可选功能，未授权时手动输入配对码）。
 */
class MangoNotificationService : NotificationListenerService() {
    companion object {
        val capturedCode = MutableStateFlow<String?>(null)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == "com.android.systemui") {
            val extras = sbn.notification.extras
            // 通知文本可能为空，空安全处理避免 NPE 崩溃
            val text = extras.getCharSequence("android.text")?.toString() ?: return
            // title 可选：部分 ROM 配对通知无标题，不能因缺 title 就丢弃
            val title = extras.getCharSequence("android.title")?.toString()
            val codeRegex = Regex("\\b\\d{6,8}\\b")
            codeRegex.find(text)?.let { match ->
                val hit = text.contains("配对") || text.contains("代码") ||
                    (title?.contains("配对") == true)
                if (hit) capturedCode.value = match.value
            }
        }
    }
}
