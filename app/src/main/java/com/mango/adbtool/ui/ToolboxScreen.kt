package com.mango.adbtool.ui
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mango.adbtool.MainViewModel
import com.mango.adbtool.ui.theme.*
import kotlinx.coroutines.launch
private data class Tool(val key: String, val emoji: String, val name: String, val desc: String)
private val TOOLS = listOf(
    Tool("info", "📟", "设备信息", "getprop 全家桶"),
    Tool("apps", "🍎", "应用管家", "冻结·卸载·清数据"),
    Tool("input", "👆", "输入模拟", "点按·滑动·按键"),
    Tool("shot", "📸", "一键截屏", "shell 权限截屏"),
    Tool("log", "📜", "日志查看", "logcat 抓取"),
    Tool("perm", "🔐", "权限管家", "grant / revoke"),
    Tool("quick", "⚡", "快捷开关", "svc · settings"),
    Tool("install", "📦", "静默安装", "pm install -r"),
    Tool("flash", "🧱", "模块刷入", "推送并执行脚本")
)
@Composable
fun ToolboxScreen(vm: MainViewModel) {
    var page by rememberSaveable { mutableStateOf("home") }
    if (page == "home") {
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            items(TOOLS) { t ->
                GlassCard(onClick = { page = t.key }) {
                    Text(t.emoji, fontSize = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t.name, fontWeight = FontWeight.Bold, color = CocoaInk, fontSize = 15.sp)
                    Text(t.desc, fontSize = 11.sp, color = CocoaInkLight)
                }
            }
        }
    } else {
        ToolScaffold(TOOLS.first { it.key == page }.name, { page = "home" }) {
            when (page) {
                "info" -> DeviceInfoPage(vm)
                "apps" -> AppManagerPage(vm)
                "input" -> InputPage(vm)
                "shot" -> ScreenshotPage(vm)
                "log" -> LogPage(vm)
                "perm" -> PermPage(vm)
                "quick" -> QuickPage(vm)
                "install" -> InstallApkPage(vm)
                "flash" -> FlashPage(vm)
            }
        }
    }
}
@Composable
private fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            GlassButton("返回", "←", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            SectionTitle(title)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
@Composable
fun DeviceInfoPage(vm: MainViewModel) {
    var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val out = runCatching { vm.manager.exec("getprop") }.getOrDefault("")
        props = out.lines().mapNotNull { l -> Regex("^\\[(.+?)]: \\[(.*)]$").find(l)?.let { it.groupValues[1] to it.groupValues[2] } }.toMap()
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            GlassCard {
                listOf("ro.product.brand" to "品牌", "ro.product.model" to "型号", "ro.build.version.release" to "Android 版本", "ro.build.version.sdk" to "SDK", "ro.build.version.security_patch" to "安全补丁", "ro.product.cpu.abi" to "CPU 架构").forEach { (k, label) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = CocoaInkLight)
                        Text(props[k] ?: "…", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CocoaInk)
                    }
                }
            }
        }
        items(props.entries.toList()) { (k, v) ->
            GlassCard(contentPadding = PaddingValues(12.dp)) {
                Text(k, fontSize = 10.5.sp, color = CocoaInkLight, fontFamily = FontFamily.Monospace)
                Text(v.ifBlank { "(空)" }, fontSize = 12.5.sp, color = CocoaInk, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
@Composable
fun AppManagerPage(vm: MainViewModel) {
    var apps by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    var onlyFrozen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            loading = true
            val cmd = if (onlyFrozen) "pm list packages -d" else "pm list packages -3"
            apps = runCatching { vm.manager.exec(cmd) }.getOrDefault("").lines().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.sorted()
            loading = false
        }
    }
    LaunchedEffect(onlyFrozen) { reload() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(vertical = 8.dp)) {
            GlassChip("全部应用", !onlyFrozen) { onlyFrozen = false }
            Spacer(Modifier.width(8.dp))
            GlassChip("已冻结", onlyFrozen) { onlyFrozen = true }
        }
        if (loading) Text("🍈 努力加载中…", color = CocoaInkLight, fontSize = 13.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(apps) { pkg ->
                GlassCard(contentPadding = PaddingValues(12.dp), onClick = { selected = pkg }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pkg, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = CocoaInk)
                        Text("管理 ▸", fontSize = 11.sp, color = CocoaInkLight)
                    }
                }
            }
        }
    }
    selected?.let { pkg ->
        var msg by remember { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = { selected = null }) {
            GlassCard {
                SectionTitle("🍎 $pkg")
                Spacer(Modifier.height(10.dp))
                FlowButtons(listOf(
                    "🧊 冻结" to "pm disable-user --user 0 $pkg", "☀️ 解冻" to "pm enable $pkg",
                    "💥 强停" to "am force-stop $pkg", "🧹 清数据" to "pm clear $pkg",
                    "🗑 卸载" to "pm uninstall -k --user 0 $pkg", "♻️ 恢复卸载" to "cmd package install-existing $pkg",
                    "🚀 启动" to "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
                )) { cmd -> scope.launch { msg = runCatching { vm.manager.exec(cmd) }.getOrElse { it.message }; reload() } }
                msg?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 11.5.sp, color = CocoaInkLight, maxLines = 3) }
            }
        }
    }
}
@Composable
private fun FlowButtons(items: List<Pair<String, String>>, onCmd: (String) -> Unit) {
    items.chunked(2).forEach { row ->
        Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, cmd) -> GlassButton(label, emoji = "", onClick = { onCmd(cmd) }) }
        }
    }
}
@Composable
fun InputPage(vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var x by remember { mutableStateOf("540") }
        var y by remember { mutableStateOf("1200") }
        GlassCard {
            SectionTitle("👆 点击坐标")
            Row {
                GlassTextField(x, { x = it }, "x", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GlassTextField(y, { y = it }, "y", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GlassButton("点它", "👆") { vm.exec("input tap $x $y") }
            }
        }
        var swipe by remember { mutableStateOf("540 1600 540 400 300") }
        GlassCard {
            SectionTitle("🌊 滑动 (x1 y1 x2 y2 时长ms)")
            Row {
                GlassTextField(swipe, { swipe = it }, "x1 y1 x2 y2 ms", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GlassButton("滑", "🌊") { vm.exec("input swipe $swipe") }
            }
        }
        var text by remember { mutableStateOf("hello mango") }
        GlassCard {
            SectionTitle("⌨️ 输入文本（仅 ASCII）")
            Row {
                GlassTextField(text, { text = it }, "要打的内容", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GlassButton("打字", "✍️") { vm.exec("input text '$text'") }
            }
        }
        GlassCard {
            SectionTitle("🎛 物理按键")
            Spacer(Modifier.height(6.dp))
            val keys = listOf("返回" to 4, "主页" to 3, "菜单" to 82, "音量+" to 24, "音量-" to 25, "电源" to 26, "回车" to 66, "删除" to 67, "通知" to 87, "相机" to 27, "静音" to 164)
            keys.chunked(4).forEach { row ->
                Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (n, c) -> GlassButton(n, emoji = "") { vm.exec("input keyevent $c") } }
                }
            }
        }
    }
}
@Composable
fun ScreenshotPage(vm: MainViewModel) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("咔嚓", "📸", enabled = !busy) {
                    busy = true
                    scope.launch { bmp = vm.screenshot(); busy = false }
                }
                bmp?.let {
                    GlassButton("存相册", "💾") { msg = if (vm.saveBitmap(it)) "已保存到 Pictures/Mango 🎉" else "保存失败" }
                }
            }
            msg?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = CocoaInkLight) }
        }
        bmp?.let { Image(it.asImageBitmap(), "截图", Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))) }
    }
}
@Composable
fun LogPage(vm: MainViewModel) {
    var logs by remember { mutableStateOf(listOf<String>()) }
    var filter by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        scope.launch {
            logs = runCatching { vm.manager.exec("logcat -d -t 500") }.getOrElse { it.message ?: "" }.lines()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(filter, { filter = it }, "过滤关键字…", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            GlassButton("刷新", "🔄") { refresh++ }
        }
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp)) {
            LazyColumn {
                items(logs.filter { it.contains(filter, ignoreCase = true) }) { l ->
                    Text(l, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = CocoaInk, lineHeight = 14.sp)
                }
            }
        }
    }
}
@Composable
fun PermPage(vm: MainViewModel) {
    var pkg by remember { mutableStateOf("") }
    var perm by remember { mutableStateOf("android.permission.WRITE_SECURE_SETTINGS") }
    val suggestions = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.READ_LOGS", "android.permission.PACKAGE_USAGE_STATS", "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            SectionTitle("🔐 授权 / 撤销危险权限")
            Spacer(Modifier.height(8.dp))
            GlassTextField(pkg, { pkg = it }, "目标应用包名", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            GlassTextField(perm, { perm = it }, "权限名", Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("授权", "✅") { vm.exec("pm grant $pkg $perm") }
                GlassButton("撤销", "🚫") { vm.exec("pm revoke $pkg $perm") }
            }
        }
        GlassCard {
            SectionTitle("🍬 常用权限（点一下填入）")
            suggestions.forEach { p ->
                Text(p, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = CocoaInk, modifier = Modifier.padding(vertical = 5.dp))
            }
        }
    }
}
@Composable
fun QuickPage(vm: MainViewModel) {
    var brightness by remember { mutableStateOf(120f) }
    val quick = listOf(
        "📶 Wi-Fi 开" to "svc wifi enable", "📴 Wi-Fi 关" to "svc wifi disable",
        "🔵 蓝牙开" to "svc bluetooth enable", "⚪ 蓝牙关" to "svc bluetooth disable",
        "📊 数据开" to "svc data enable", "📵 数据关" to "svc data disable",
        "🌙 深色模式" to "cmd uimode night yes", "☀️ 浅色模式" to "cmd uimode night no",
        "🛌 30秒息屏" to "settings put system screen_off_timeout 30000",
        "☕ 5分钟息屏" to "settings put system screen_off_timeout 300000",
        "✨ 显示点按" to "settings put system show_touches 1",
        "🎈 隐藏点按" to "settings put system show_touches 0",
        "🚀 动画加速" to "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5",
        "🐢 动画还原" to "settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1"
    )
    Column {
        GlassCard {
            SectionTitle("🔆 屏幕亮度 ${brightness.toInt()}")
            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 1f..255f, onValueChangeFinished = { vm.exec("settings put system screen_brightness ${brightness.toInt()}") })
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalArrangement = Arrangement.spacedBy(11.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(quick) { (label, cmd) ->
                GlassCard(contentPadding = PaddingValues(14.dp), onClick = { vm.exec(cmd) }) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CocoaInk)
                }
            }
        }
    }
}
@Composable
fun InstallApkPage(vm: MainViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::installApk) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            SectionTitle("📦 静默安装 APK")
            Spacer(Modifier.height(6.dp))
            Text("用 shell 权限直接安装，不弹系统安装界面（pm install -r -g）。\n安装结果会打印到「终端」页。", fontSize = 12.sp, color = CocoaInkLight, lineHeight = 17.sp)
            Spacer(Modifier.height(12.dp))
            GlassButton("选择 APK", "📦") { launcher.launch(arrayOf("application/vnd.android.package-archive")) }
        }
    }
}
// 模块刷入页面
@Composable
fun FlashPage(vm: MainViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val fileName = it.lastPathSegment?.substringAfterLast('/') ?: "unknown"
            vm.flashModule(it, fileName)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            SectionTitle("🧱 刷入并执行模块")
            Spacer(Modifier.height(6.dp))
            Text("选择一个 .sh 脚本或二进制执行文件，小芒果会把它推送到 /data/local/tmp/ 并赋予可执行权限，然后立即执行它。\n执行过程和输出会打印到「终端」页。", fontSize = 12.sp, color = CocoaInkLight, lineHeight = 17.sp)
            Spacer(Modifier.height(12.dp))
            GlassButton("选择文件刷入", "🚀") { launcher.launch(arrayOf("*/*")) }
        }
    }
}
