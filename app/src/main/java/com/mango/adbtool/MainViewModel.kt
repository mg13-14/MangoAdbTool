package com.mango.adbtool
import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mango.adbtool.core.MangoManager
import com.mango.adbtool.core.MangoNotificationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val manager = MangoManager(app)
    val state get() = manager.state
    // 通知监听服务自动抓取的配对码（未授权通知使用权时恒为 null，需手动输入）
    val capturedCode = MangoNotificationService.capturedCode
    private val _terminal = MutableStateFlow(listOf("🥭 欢迎来到芒果终端！", "服务运行时，这里就是你说了算～输入 help 看常用命令"))
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()
    private val HELP_LINES = listOf(
        "── 常用命令速查 ─────────────",
        " pm list packages -3        # 第三方应用",
        " dumpsys battery            # 电池状态",
        " input tap 500 800          # 点击屏幕",
        " am start -a android.settings.SETTINGS   # 打开设置",
        " svc wifi disable           # 关 Wi-Fi",
        "─────────────────────────────"
    )
    fun appendTerminal(s: String) { _terminal.value = _terminal.value + s }
    fun exec(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        if (c == "help") { HELP_LINES.forEach(::appendTerminal); return }
        appendTerminal("shell> $c")
        viewModelScope.launch {
            val out = runCatching { manager.exec(c) }.getOrElse { "❌ ${it.message}" }
            if (out.isBlank()) appendTerminal("(无输出)") else out.lineSequence().forEach(::appendTerminal)
        }
    }
    fun startDiscovery() = viewModelScope.launch { manager.startPairingDiscovery() }
    fun pairAndStart(code: String) = viewModelScope.launch { manager.pairAndStart(code) }
    fun startViaRoot() = viewModelScope.launch { manager.startViaRoot() }
    fun stop() = viewModelScope.launch { manager.stopService() }
    suspend fun screenshot(): Bitmap? = manager.screenshot()
    fun installApk(uri: Uri) {
        appendTerminal("📦 正在静默安装…")
        viewModelScope.launch { appendTerminal(manager.installApk(uri)) }
    }
    // 刷入并执行模块脚本
    fun flashModule(uri: Uri, fileName: String) {
        appendTerminal("📦 正在刷入模块: $fileName …")
        viewModelScope.launch {
            val app = getApplication<Application>()
            val tmpFile = File(app.getExternalFilesDir(null), "mango_flash_${System.currentTimeMillis()}")
            runCatching {
                app.contentResolver.openInputStream(uri)!!.use { it.copyTo(tmpFile.outputStream()) }
                // .sh 脚本用 sh 执行；二进制文件推送后 chmod 755 直接执行
                val cmd = if (fileName.endsWith(".sh", ignoreCase = true))
                    "cp ${tmpFile.absolutePath} /data/local/tmp/mango_flash.sh && sh /data/local/tmp/mango_flash.sh"
                else
                    "cp ${tmpFile.absolutePath} /data/local/tmp/mango_flash && chmod 755 /data/local/tmp/mango_flash && /data/local/tmp/mango_flash"
                appendTerminal(manager.exec(cmd, 300_000)) // 超时 5 分钟
            }.getOrElse { appendTerminal("❌ 刷入失败: ${it.message}") }
        }
    }
    fun saveBitmap(bmp: Bitmap): Boolean = runCatching {
        val app = getApplication<Application>()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mango_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mango")
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        app.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)
}
