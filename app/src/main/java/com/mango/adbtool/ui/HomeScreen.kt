package com.mango.adbtool.ui
import android.content.Intent
import android.provider.Settings
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
    val capturedCode by vm.capturedCode.collectAsState()
    var pairDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(toast) { if (toast != null) { delay(3200); toast = null } }
    // 当发现配对端口时，自动弹窗
    LaunchedEffect(state) {
        if (state == MangoState.WAITING_FOR_CODE) pairDialog = true
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                GlassCard {
                    val isAnimating = state in listOf(MangoState.SEARCHING_PAIR, MangoState.PAIRING, MangoState.SEARCHING_SERVICE, MangoState.STARTING)
                    Text(state.emoji, fontSize = 50.sp, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(state.label, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = CocoaInk)
                    Spacer(Modifier.height(4.dp))
                    Text(state.desc, fontSize = 13.sp, color = CocoaInkLight)
                    if (isAnimating) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MangoOrange, trackColor = Color.White.copy(alpha = 0.4f))
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state == MangoState.OFFLINE || state == MangoState.FAILED) {
                            GlassButton("无线配对", "🔗") {
                                // 引导开启通知使用权，以便自动抓取配对码
                                val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                                if (enabled?.contains(context.packageName) != true) {
                                    toast = "请授予通知使用权，以便自动抓取配对码"
                                    runCatching { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
                                } else {
                                    toast = "请在系统弹窗中点击「使用配对码配对设备」"
                                }
                                runCatching { context.startActivity(Intent("com.android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
                                vm.startDiscovery()
                            }
                            GlassButton("Root 启动", "🧪") { vm.startViaRoot() }
                        }
                        if (state == MangoState.RUNNING) {
                            GlassButton("停止服务", "⏹️") { vm.stop() }
                        }
                    }
                }
            }
            item { SectionTitle("🧭 新手引导") }
            itemsIndexed(GUIDE_STEPS) { i, step -> GuideStepCard(i + 1, step) }
        }
        toast?.let {
            GlassCard(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) { Text(it, fontSize = 13.sp, color = CocoaInk, fontWeight = FontWeight.SemiBold) }
        }
    }
    // 弹窗：支持手动输入，或自动填入通知监听抓取的配对码
    if (pairDialog) {
        var code by remember(capturedCode) { mutableStateOf(capturedCode ?: "") }
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
