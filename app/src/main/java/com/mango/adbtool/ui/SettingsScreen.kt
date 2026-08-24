package com.mango.adbtool.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mango.adbtool.ui.theme.*
@Composable
fun SettingsScreen() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(30.dp))
        Text("🥭", fontSize = 64.sp)
        Text("芒果提权工具", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = CocoaInk)
        Text("v1.4.0 · 液态玻璃版", fontSize = 12.sp, color = CocoaInkLight)
        Spacer(Modifier.height(24.dp))
        // 关于本软件
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("ℹ️ 关于本软件")
            Spacer(Modifier.height(10.dp))
            Text(
                "芒果提权工具是一款在 Android 设备上本地运行 ADB 提权服务的工具。\n" +
                "它通过系统自带的「无线调试」功能，以 ADB Shell (uid 2000) 权限拉起一个后台服务进程，从而摆脱了传统 USB 连接电脑的束缚，也无需对设备进行 Root。\n\n" +
                "服务进程独立于 App 存活，即使 App 被系统杀死，提权服务依然在后台正常运行。",
                fontSize = 12.5.sp, color = CocoaInkLight, lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        // 工作原理
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("⚙️ 工作原理")
            Spacer(Modifier.height(10.dp))
            Text(
                "1️⃣ App 通过系统 mDNS 服务发现（NsdManager）监听配对界面。\n" +
                "2️⃣ 用户填入配对码，App 将自己的 ADB 公钥交给 adbd。\n" +
                "3️⃣ App 再次定位真正的服务端口，通过本机回环 ADB 执行 app_process。\n" +
                "4️⃣ 启动 shell(uid 2000) 权限的服务进程，App 通过 abstract socket 与其通信。\n" +
                "5️⃣ 借服务进程之手，执行 pm / am / settings / input 等高权限命令。\n\n" +
                "已 Root 的设备（Magisk / KernelSU）可直接「Root 启动」，跳过全部配对流程。",
                fontSize = 12.5.sp, color = CocoaInkLight, lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        // 致谢名单
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("🙏 特别致谢")
            Spacer(Modifier.height(10.dp))
            Text("感谢以下小伙伴对本项目开发与测试做出的杰出贡献：", fontSize = 12.5.sp, color = CocoaInkLight, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            // 使用高亮颜色和粗体显示致谢人
            Text(
                "mg13-14",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MangoOrange,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Made with 🥭 and 🫧", fontSize = 11.sp, color = CocoaInkLight, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(40.dp)) // 滚动底部留白
    }
}
