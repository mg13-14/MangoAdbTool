package com.mango.adbtool
import android.content.Intent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mango.adbtool.core.MangoEvents
import com.mango.adbtool.ui.HomeScreen
import com.mango.adbtool.ui.SettingsScreen
import com.mango.adbtool.ui.TerminalScreen
import com.mango.adbtool.ui.ToolboxScreen
import com.mango.adbtool.ui.theme.*
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { MangoApp() }
    }
    // singleTop 模式下，点通知回跳已存在的实例走这里
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    override fun onResume() { super.onResume(); MangoEvents.inForeground = true }
    override fun onPause() { super.onPause(); MangoEvents.inForeground = false }
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_pair_dialog", false) == true) {
            MangoEvents.openPairDialog.value = true
        }
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
                    else -> SettingsScreen() // 设置页（含关于/原理/致谢）
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
    val items = listOf("首页", "终端", "工具", "设置")
    val emojis = listOf("🏠", "⌨️", "🧰", "⚙️")
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
