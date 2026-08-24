package com.mango.adbtool.ui
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mango.adbtool.MainViewModel
import com.mango.adbtool.core.MangoManager
import com.mango.adbtool.core.MangoState
import com.mango.adbtool.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var port by rememberSaveable { mutableStateOf("") }
    var pairDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(toast) { if (toast != null) { delay(3200); toast = null } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item {
                GlassCard {
                    val bounce = rememberInfiniteTransition(label = "b").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "bv").value
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.emoji, fontSize = 46.sp, modifier = Modifier.graphicsLayer { translationY = if (state == MangoState.RUNNING) -7f * bounce else 0f })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.label, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = CocoaInk)
                            Text(if (state == MangoState.RUNNING) "已获得 shell(uid 2000) 权限，尽情折腾吧 🎉" else "芒果服务还没醒来，跟着下面的步骤慢慢滚～", fontSize = 12.5.sp, color = CocoaInkLight)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("开始配对", "🔗") { pairDialog = true }
                        GlassButton("一键启动", "🚀", enabled = state != MangoState.STARTING) {
                            vm.startWireless(port) {
                                toast = if (it.isSuccess) "服务启动成功，小芒果上岗啦 🎉" else "启动失败：${it.exceptionOrNull()?.message}"
                            }
                        }
                        if (state == MangoState.RUNNING) GlassButton("停止", "⏹️") { vm.stop() }
                    }
                }
            }
            item {
                GlassCard {
                    SectionTitle("📡 服务端口")
                    Spacer(Modifier.height(6.dp))
                    Text("开发者选项 → 无线调试页面顶部显示的端口号。\n每次重开无线调试端口都会变，记得回来改～", fontSize = 12.sp, color = CocoaInkLight, lineHeight = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(port, { port = it }, "例如 37099", Modifier.fillMaxWidth())
                }
            }
            item {
                GlassCard {
                    SectionTitle("🔌 电脑党一键命令（可选）")
                    Spacer(Modifier.height(6.dp))
                    Text("不想用无线？数据线连电脑，复制下面命令执行一次即可（无需配对）", fontSize = 12.sp, color = CocoaInkLight)
                    Spacer(Modifier.height(10.dp))
                    Text(MangoManager.USB_CMD, fontSize = 10.5.sp, color = CocoaInk, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    GlassButton("复制命令", "📋") { clipboard.setText(AnnotatedString(MangoManager.USB_CMD)); toast = "已复制，去电脑上运行吧 💻" }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("🧭 新手引导 · 从第 1 步滚到最后一步")
                Spacer(Modifier.height(4.dp))
            }
            itemsIndexed(GUIDE_STEPS) { i, step -> GuideStepCard(i + 1, step) }
        }
        toast?.let {
            GlassCard(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) { Text(it, fontSize = 13.sp, color = CocoaInk, fontWeight = FontWeight.SemiBold) }
        }
    }
    if (pairDialog) {
        var addr by remember { mutableStateOf("127.0.0.1:") }
        var code by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!busy) pairDialog = false }) {
            GlassCard {
                SectionTitle("🔗 与无线调试配对")
                Spacer(Modifier.height(4.dp))
                Text("填「使用配对码配对设备」弹窗里的地址和 6-8 位配对码", fontSize = 11.5.sp, color = CocoaInkLight)
                Spacer(Modifier.height(12.dp))
                GlassTextField(addr, { addr = it }, "配对地址，如 127.0.0.1:39999", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                GlassTextField(code, { code = it }, "6-8 位配对码", Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("配对", "🤝", enabled = !busy && code.length in 6..8) {
                        busy = true; result = null
                        scope.launch {
                            val r = vm.pair(addr, code)
                            result = if (r.isSuccess) "配对成功！小芒果记住你啦 🥭" else "配对失败：${r.exceptionOrNull()?.message}"
                            busy = false
                        }
                    }
                    GlassButton("关闭", "🍦", enabled = !busy) { pairDialog = false }
                }
                result?.let { Spacer(Modifier.height(10.dp)); Text(it, fontSize = 12.5.sp, color = CocoaInk) }
            }
        }
    }
}
