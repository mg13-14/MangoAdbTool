package com.mango.adbtool.core
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** 全局事件：通知点击回跳 & 前后台标记 */
object MangoEvents {
    val openPairDialog = MutableStateFlow(false)
    @Volatile var inForeground = false
}

object MangoNotifier {
    private const val CHANNEL_ID = "mango_pairing"
    private const val NOTIFY_ID = 1001

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "配对提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "检测到系统配对界面时提醒你回来填码"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /** 后台发现配对界面时，发一条可点击回跳的高优先级通知 */
    fun notifyPairingDetected(context: Context) {
        ensureChannel(context)
        val intent = Intent(context, com.mango.adbtool.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_pair_dialog", true)
        }
        val pi = PendingIntent.getActivity(
            context, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("🥭 检测到配对界面！")
            .setContentText("小芒果已锁定配对端口，点这里回来填入配对码")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // Android 13+ 未授权时 notify 会抛 SecurityException，吞掉防崩溃
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, n) }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
    }
}
