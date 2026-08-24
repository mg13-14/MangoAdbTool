package com.mango.adbtool.core
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
class MangoNotificationService : NotificationListenerService() {
    companion object {
        // 全局暴露捕获到的配对码
        val capturedCode = MutableStateFlow<String?>(null)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 无线调试的配对码通常由系统 UI (com.android.systemui) 发出
        if (sbn?.packageName == "com.android.systemui") {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title").orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            // 正则匹配 6-8 位的纯数字配对码
            val codeRegex = Regex("\\b\\d{6,8}\\b")
            codeRegex.find(text)?.let { match ->
                if (title.contains("配对") || text.contains("配对") || text.contains("代码")) {
                    capturedCode.value = match.value
                }
            }
        }
    }
}
