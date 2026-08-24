package com.mango.adbtool.ui.theme
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
// 🎨 芒果调色盘
val MangoYellow = Color(0xFFFFC93C)
val MangoOrange = Color(0xFFFF9F45)
val MangoOrangeDark = Color(0xFFE07A2E)
val MangoLeaf = Color(0xFF8ED081)
val MangoPink = Color(0xFFFFB5C2)
val MangoSky = Color(0xFFA7D8F0)
val CocoaInk = Color(0xFF4E3A1E)
val CocoaInkLight = Color(0xFF8C7452)
@Composable
fun MangoBackground(modifier: Modifier = Modifier, animate: Boolean = true) {
    val t = if (animate) {
        val transition = rememberInfiniteTransition(label = "bg")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(30_000, easing = LinearEasing)), label = "t").value
    } else 0.35f
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFFF8E7), Color(0xFFFFE7C2))))
        val r = size.minDimension
        val a = t * 2f * PI.toFloat()
        fun jelly(cx: Float, cy: Float, radius: Float, color: Color) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent)), radius, Offset(cx, cy))
        }
        jelly(size.width * (0.15f + 0.06f * cos(a)), size.height * (0.10f + 0.04f * sin(a)), r * 0.55f, MangoYellow)
        jelly(size.width * (0.90f + 0.05f * sin(a)), size.height * (0.30f + 0.05f * cos(a)), r * 0.45f, MangoPink)
        jelly(size.width * (0.25f + 0.06f * sin(a * 1.3f)), size.height * (0.85f + 0.04f * cos(a)), r * 0.50f, MangoSky)
        jelly(size.width * (0.80f + 0.06f * cos(a * 0.8f)), size.height * (0.90f + 0.04f * sin(a)), r * 0.42f, MangoLeaf)
    }
}
// blur API 兼容降级
fun Modifier.liquidGlassBlur(): Modifier = composed {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.blur(26.dp)
    } else {
        this.drawWithContent {
            drawContent()
            drawRect(Color.White.copy(alpha = 0.35f))
        }
    }
}
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    val base = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier.clip(shape)
    Box(base.background(Color.White.copy(alpha = 0.05f))) {
        MangoBackground(Modifier.matchParentSize().liquidGlassBlur(), animate = false)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(0.62f), Color.White.copy(0.28f)))))
        Box(Modifier.matchParentSize().border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(0.95f), Color.White.copy(0.15f))), shape))
        Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).fillMaxWidth(0.4f).height(14.dp).blur(8.dp).background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(100)))
        Column(Modifier.padding(contentPadding), content = content)
    }
}
@Composable
fun GlassButton(text: String, emoji: String = "🥭", enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.35f), label = "scale")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(100))
            .background(if (enabled) Brush.horizontalGradient(listOf(MangoYellow, MangoOrange)) else Brush.horizontalGradient(listOf(Color(0xFFE5D9C3), Color(0xFFDACBB0))))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text("$emoji $text".trim(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}
@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(100))
            .background(if (selected) Brush.horizontalGradient(listOf(MangoYellow, MangoOrange)) else Brush.horizontalGradient(listOf(Color.White.copy(0.7f), Color.White.copy(0.4f))))
            .border(1.dp, Color.White.copy(0.8f), RoundedCornerShape(100))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else CocoaInk) }
}
@Composable
fun GlassTextField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(color = CocoaInk, fontSize = 14.sp),
        modifier = modifier.clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .border(1.dp, Color.White, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(hint, color = CocoaInkLight, fontSize = 14.sp) else inner() }
    )
}
@Composable
fun StepBadge(number: Int) {
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color.White.copy(0.95f), Color.White.copy(0.5f))))
            .border(1.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text("$number", color = CocoaInk, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
}
@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = CocoaInk)
}
