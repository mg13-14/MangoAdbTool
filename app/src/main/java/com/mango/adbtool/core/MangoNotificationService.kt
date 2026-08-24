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
            // title 是可选字段：部分 ROM 的配对通知没有标题只有正文，不能整条丢弃
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            // 没有正文就没码可抓，直接跳过
            val text = extras.getCharSequence("android.text")?.toString() ?: return
            val codeRegex = Regex("\\b\\d{6,8}\\b")
            codeRegex.find(text)?.let { match ->
                // title 也参与关键词匹配，提升各 ROM 的抓码命中率
                if (title.contains("配对") || title.contains("代码") ||
                    text.contains("配对") || text.contains("代码")) {
                    capturedCode.value = match.value
                }
            }
        }
    }
}
