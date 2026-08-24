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
    GuideStep("点「无线配对」", "回到首页点按钮，小芒果自动跳转设置并通过 mDNS 监听配对服务"),
    GuideStep("打开配对码弹窗", "在系统里点「使用配对码配对设备」，App 会自动弹出输码框"),
    GuideStep("填入配对码", "把系统弹窗上的 6-8 位码填进去，点「一键启动」"),
    GuideStep("Root 用户更省事", "已 Root 的设备（Magisk/KernelSU）直接点「Root 启动」，无需配对"),
    GuideStep("确认绿灯", "状态卡变成 🥭 运行中，就能玩转全部功能啦"),
    GuideStep("重启之后", "手机重启后服务会休息，重新启动一次即可"),
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
