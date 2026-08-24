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
import com.mango.adbtool.core.MangoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val manager = MangoManager(app)
    val state get() = manager.state
    val capturedCode = MangoNotificationService.capturedCode
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
    fun autoStart(addr: String, code: String) {
        viewModelScope.launch { manager.autoStart(addr, code) }
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
