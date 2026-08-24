package com.mango.adbtool.ui
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mango.adbtool.ui.theme.CocoaInk
import com.mango.adbtool.ui.theme.CocoaInkLight
import com.mango.adbtool.ui.theme.GlassCard
import com.mango.adbtool.ui.theme.StepBadge
import kotlinx.coroutines.delay
data class GuideStep(val title: String, val desc: String)
val GUIDE_STEPS = listOf(
    GuideStep("打开开发者选项", "设置 → 关于手机 → 连续点「版本号」7 次"),
    GuideStep("开启无线调试", "开发者选项 → 打开「无线调试」开关（需要 Android 11+）"),
    GuideStep("拿到配对码", "点「使用配对码配对设备」，记下 IP:端口 和 6-8 位配对码"),
    GuideStep("回本应用配对", "点首页「开启配对」填入地址和码（授予通知权限可自动填码）"),
    GuideStep("一键启动", "点「一键启动」，小芒果自动配对、扫描端口、拉起提权服务"),
    GuideStep("确认绿灯", "状态卡变成 🥭 运行中，就能玩转全部功能啦"),
    GuideStep("电脑党可选", "不想无线？用数据线连电脑，执行首页的 USB 命令一次"),
    GuideStep("重启之后", "手机重启后服务会休息，再点一次「开启配对」唤醒它"),
)
@Composable
fun GuideStepCard(index: Int, step: GuideStep) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 60L)
        shown.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 220f))
    }
    GlassCard(
        Modifier.graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 50f },
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge(index)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(step.title, fontWeight = FontWeight.Bold, color = CocoaInk, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(step.desc, color = CocoaInkLight, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}
