package com.mango.adbtool
import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mango.adbtool.core.MangoManager
import com.mango.adbtool.core.MangoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val manager = MangoManager(app)
    val state get() = manager.state
    private val _terminal = MutableStateFlow(listOf("🥭 欢迎来到芒果终端！", "服务运行时，这里就是你说了算～输入 help 看常用命令"))
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()
    private val HELP_LINES = listOf(
        "── 常用命令速查 ─────────────",
        " pm list packages -3        # 第三方应用",
        " dumpsys battery            # 电池状态",
        " input tap 500 800          # 点击屏幕",
        " settings get global airplane_mode_on",
        " am start -a android.settings.SETTINGS   # 打开设置",
        " svc wifi disable           # 关 Wi-Fi",
        " logcat -d -t 100           # 最近 100 行日志",
        " getprop ro.product.model   # 机型",
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
    suspend fun pair(addr: String, code: String): Result<Unit> {
        val idx = addr.lastIndexOf(':')
        if (idx <= 0) return Result.failure(IllegalArgumentException("地址要像 127.0.0.1:39999 这样填"))
        val host = addr.substring(0, idx).ifBlank { "127.0.0.1" }
        val port = addr.substring(idx + 1).toIntOrNull() ?: return Result.failure(IllegalArgumentException("端口要是数字呀"))
        return manager.pair(host, port, code.trim())
    }
    fun startWireless(portText: String, onDone: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val port = portText.trim().toIntOrNull()
            val r = if (port == null) Result.failure(IllegalArgumentException("先填好服务端口")) else manager.startViaWireless(port)
            onDone(r)
        }
    }
    fun stop() = viewModelScope.launch { manager.stopService() }
    suspend fun screenshot(): Bitmap? = manager.screenshot()
    fun installApk(uri: Uri) {
        appendTerminal("📦 正在静默安装…")
        viewModelScope.launch { appendTerminal(manager.installApk(uri)) }
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
