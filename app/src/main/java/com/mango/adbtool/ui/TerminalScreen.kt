package com.mango.adbtool.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mango.adbtool.MainViewModel
import com.mango.adbtool.ui.theme.*
@Composable
fun TerminalScreen(vm: MainViewModel) {
    val logs by vm.terminal.collectAsState()
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        GlassCard(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(14.dp)) {
            LazyColumn(state = listState) {
                items(logs) { l ->
                    Text(l, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (l.startsWith("shell>")) MangoOrangeDark else CocoaInk, lineHeight = 17.sp)
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(cmd, { cmd = it }, "输入 shell 命令…help 求助", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            GlassButton("运行", "▶") { vm.exec(cmd); cmd = "" }
        }
    }
}
