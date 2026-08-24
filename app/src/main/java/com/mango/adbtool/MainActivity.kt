package com.mango.adbtool
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mango.adbtool.ui.HomeScreen
import com.mango.adbtool.ui.TerminalScreen
import com.mango.adbtool.ui.ToolboxScreen
import com.mango.adbtool.ui.theme.*
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MangoApp() }
    }
}
@Composable
fun MangoApp() {
    val vm: MainViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        MangoBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MangoTopBar(vm)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(vm)
                    1 -> TerminalScreen(vm)
                    2 -> ToolboxScreen(vm)
                    else -> AboutScreen()
                }
            }
            MangoBottomBar(tab) { tab = it }
        }
    }
}
@Composable
private fun MangoTopBar(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🥭", fontSize = 30.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("芒果提权工具", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = CocoaInk)
            Text("Mango ADB Tool · 液态玻璃版", fontSize = 10.5.sp, color = CocoaInkLight)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(100))
                .background(Brush.verticalGradient(listOf(Color.White.copy(0.8f), Color.White.copy(0.45f))))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text(state.emoji, fontSize = 15.sp) }
    }
}
@Composable
private fun MangoBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("首页", "终端", "工具", "关于")
    val emojis = listOf("🏠", "⌨️", "🧰", "🥭")
    GlassCard(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), corner = 30.dp, contentPadding = PaddingValues(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { i, name ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected == i) Brush.verticalGradient(listOf(MangoYellow.copy(0.85f), MangoOrange.copy(0.7f))) else Brush.verticalGradient(listOf(Color.White.copy(0.4f), Color.White.copy(0.15f))))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(emojis[i], fontSize = 18.sp)
                    Text(name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (selected == i) Color.White else CocoaInk)
                }
            }
        }
    }
}
@Composable
private fun AboutScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Text("🥭", fontSize = 64.sp)
        Text("芒果提权工具", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = CocoaInk)
        Text("v1.0.0 · 让小芒果替你握住 ADB", fontSize = 12.sp, color = CocoaInkLight)
        Spacer(Modifier.height(20.dp))
        GlassCard {
            Text("⚙️ 工作原理", fontWeight = FontWeight.Bold, color = CocoaInk, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text("1️⃣ 与系统「无线调试」配对，把 ADB 公钥交给 adbd\n2️⃣ 通过本机回环 ADB 执行 app_process，启动 shell(uid 2000) 权限的服务进程\n3️⃣ App 通过 abstract socket 与服务对话，借它之手执行 pm / am / settings / input 等高权限命令", fontSize = 12.5.sp, color = CocoaInkLight, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(14.dp))
        GlassCard {
            Text("📜 说明", fontWeight = FontWeight.Bold, color = CocoaInk, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text("· 本项目为原创教学实现，原理上致敬开源项目 Shizuku，未使用其任何代码\n· 提权级别为 ADB shell，不是 root\n· 请仅在你自己的设备上使用，遵守当地法律法规\n· 服务进程独立于 App 存活，App 被杀也不影响", fontSize = 12.5.sp, color = CocoaInkLight, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("Made with 🥭 and 🫧", fontSize = 11.sp, color = CocoaInkLight, fontFamily = FontFamily.Monospace)
    }
}
