package com.lyrix.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object Sp {
    val Bg0 = Color(0xFF05030F)
    val Bg1 = Color(0xFF0D0B2A)
    val Card = Color(0xFF14122F)
    val Violet = Color(0xFF9B7BFF)
    val Cyan = Color(0xFF5EE6FF)
    val Pink = Color(0xFFFF6BD6)
    val Muted = Color(0xFFA29FCB)
    val Good = Color(0xFF5CE08A)
    val Bad = Color(0xFFFF8A9B)
}

@Composable
fun SpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sp.Violet,
            secondary = Sp.Cyan,
            background = Sp.Bg0,
            surface = Sp.Card,
            onSurface = Color.White,
            onBackground = Color.White
        ),
        content = content
    )
}

private class Star(val x: Float, val y: Float, val r: Float, val ph: Float)

/** Мерцающие звёзды и туманности. */
@Composable
fun StarField(modifier: Modifier = Modifier) {
    val stars = remember {
        val rnd = Random(7)
        List(110) { Star(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat() * 1.4f, rnd.nextFloat()) }
    }
    val inf = rememberInfiniteTransition(label = "stars")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c1 = Offset(w * 0.15f, h * 0.1f)
        val c2 = Offset(w * 0.95f, h * 0.55f)
        drawCircle(Brush.radialGradient(listOf(Sp.Violet.copy(alpha = 0.28f), Color.Transparent), center = c1, radius = w * 0.9f), radius = w * 0.9f, center = c1)
        drawCircle(Brush.radialGradient(listOf(Sp.Cyan.copy(alpha = 0.16f), Color.Transparent), center = c2, radius = w * 0.8f), radius = w * 0.8f, center = c2)
        stars.forEach { s ->
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(((t + s.ph) * 2f * PI).toFloat()))
            val yy = (s.y + t * 0.03f * s.r) % 1f
            drawCircle(Color.White.copy(alpha = tw), radius = s.r * density, center = Offset(s.x * w, yy * h))
        }
    }
}

@Composable
fun SpaceBackground(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Brush.verticalGradient(listOf(Sp.Bg1, Sp.Bg0)))) {
        StarField(Modifier.matchParentSize())
        content()
    }
}

/** Кнопка с пружинным «нажатием». */
fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "bounce"
    )
    this
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Sp.Card.copy(alpha = 0.72f))
            .border(1.dp, Brush.linearGradient(listOf(Sp.Violet.copy(0.55f), Sp.Cyan.copy(0.18f), Sp.Pink.copy(0.3f))), shape)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun SpaceButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .then(if (enabled) Modifier.bounceClick(onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .then(
                if (filled) Modifier.background(Brush.horizontalGradient(listOf(Sp.Violet, Sp.Cyan)))
                else Modifier.background(Color(0x1FFFFFFF)).border(1.dp, Color(0x33FFFFFF), shape)
            )
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) Sp.Bg0 else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun SpaceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Sp.Violet.copy(alpha = 0.9f) else Color(0x1FFFFFFF), tween(250), label = "chipBg")
    val fg by animateColorAsState(if (selected) Color.White else Sp.Muted, tween(250), label = "chipFg")
    Box(
        Modifier
            .bounceClick(onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun EnterItem(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 5 }
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun HintText(text: String) {
    Text(text, color = Sp.Muted, fontSize = 12.sp)
}

@Composable
fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Sp.Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Sp.Violet,
                uncheckedThumbColor = Sp.Muted,
                uncheckedTrackColor = Color(0x33FFFFFF),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(valueText, color = Sp.Cyan, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Sp.Cyan,
                activeTrackColor = Sp.Violet,
                inactiveTrackColor = Color(0x33FFFFFF)
            )
        )
    }
}

@Composable
fun GradientTitle(text: String, size: Int = 38) {
    Text(
        text,
        style = TextStyle(
            brush = Brush.linearGradient(listOf(Sp.Violet, Sp.Cyan, Sp.Pink)),
            fontSize = size.sp,
            fontWeight = FontWeight.Black
        )
    )
}

fun Modifier.sizeDp(s: Dp): Modifier = this.size(s)
