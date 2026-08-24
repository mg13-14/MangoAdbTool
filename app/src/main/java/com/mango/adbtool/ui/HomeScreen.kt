package com.mango.adbtool.ui
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mango.adbtool.MainViewModel
import com.mango.adbtool.core.MangoState
import com.mango.adbtool.ui.theme.*
import kotlinx.coroutines.delay
@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var pairDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(toast) { if (toast != null) { delay(3200); toast = null } }
    // 监听状态：当扫描到配对端口时，自动弹窗
    LaunchedEffect(state) {
        if (state == MangoState.WAITING_FOR_CODE) pairDialog = true
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 主状态卡：进度展示区
            item {
                GlassCard {
                    val isAnimating = state in listOf(MangoState.SEARCHING_PAIR, MangoState.PAIRING, MangoState.SEARCHING_SERVICE, MangoState.STARTING)
                    val bounce = rememberInfiniteTransition(label = "b").animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bv").value
                    Text(state.emoji, fontSize = 50.sp, modifier = Modifier.padding(start = 8.dp).graphicsLayer { translationY = if (isAnimating) -7f * bounce else 0f })
                    Spacer(Modifier.height(8.dp))
                    Text(state.label, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = CocoaInk)
                    Spacer(Modifier.height(4.dp))
                    Text(state.desc, fontSize = 13.sp, color = CocoaInkLight)
                    if (isAnimating) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MangoOrange,
                            trackColor = Color.White.copy(alpha = 0.4f)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state == MangoState.OFFLINE || state == MangoState.FAILED) {
                            // 1. 无线调试启动
                            GlassButton("无线配对", "🔗") {
                                runCatching {
                                    context.startActivity(Intent("com.android.settings.WIRELESS_DEBUGGING_SETTINGS"))
                                }
                                toast = "请点击系统弹窗中的「使用配对码配对设备」"
                                vm.startDiscovery()
                            }
                            // 2. Root 启动 (像 Shizuku 一样)
                            GlassButton("Root 启动", "🧪") {
                                vm.startViaRoot()
                            }
                        }
                        if (state == MangoState.RUNNING) {
                            GlassButton("停止服务", "⏹️") { vm.stop() }
                        }
                    }
                }
            }
            // 电脑党备选方案
            item {
                GlassCard {
                    SectionTitle("🔌 没有无线调试/Root？用电脑")
                    Spacer(Modifier.height(6.dp))
                    Text("如果你的系统低于 Android 11 且无 Root，请用数据线连电脑，执行以下命令一次即可：", fontSize = 12.sp, color = CocoaInkLight)
                    Spacer(Modifier.height(10.dp))
                    Text("adb shell sh -c 'cp /storage/emulated/0/Android/data/com.mango.adbtool/files/mango-server.dex /data/local/tmp/ && app_process /system/bin com.mango.adbtool.server.ServerMain'", fontSize = 10.sp, color = CocoaInk, lineHeight = 15.sp)
                }
            }
            // 引导步骤
            item { SectionTitle("🧭 新手引导") }
            itemsIndexed(GUIDE_STEPS) { i, step -> GuideStepCard(i + 1, step) }
        }
        toast?.let {
            GlassCard(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) { Text(it, fontSize = 13.sp, color = CocoaInk, fontWeight = FontWeight.SemiBold) }
        }
    }
    // 配对码弹窗（全自动触发，仅需填码）
    if (pairDialog) {
        var code by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { if (state != MangoState.WAITING_FOR_CODE) pairDialog = false }) {
            GlassCard {
                SectionTitle("✨ 已抓取到端口")
                Spacer(Modifier.height(4.dp))
                Text("小芒果已自动检测到配对界面！\n请填入系统弹窗上显示的 6-8 位配对码：", fontSize = 12.5.sp, color = CocoaInkLight)
                Spacer(Modifier.height(14.dp))
                GlassTextField(code, { code = it }, "在此输入配对码", Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("一键启动", "🚀", enabled = code.length in 6..8) {
                        pairDialog = false
                        vm.pairAndStart(code)
                    }
                    GlassButton("取消", "🍦") { pairDialog = false }
                }
            }
        }
    }
}
