@file:OptIn(ExperimentalMaterial3Api::class)

package com.lyrix.overlay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

data class PermState(val overlay: Boolean = false, val listener: Boolean = false)

private val Bg = Color(0xFF0E0E14)
private val CardBg = Color(0xFF181822)
private val Accent = Color(0xFF8B6CFF)
private val Accent2 = Color(0xFF35D0FF)
private val Good = Color(0xFF5CE08A)
private val Muted = Color(0xFF9A9AB0)

@Composable
fun LyrixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Accent2,
            background = Bg,
            surface = CardBg,
            onSurface = Color.White,
            onBackground = Color.White
        ),
        content = content
    )
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun EnterItem(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 80L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 5 }
    ) { content() }
}

@Composable
private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardBg)
            .animateContentSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
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
            Text(valueText, color = Accent2, fontSize = 14.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun PowerButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulse by inf.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseValue"
    )
    val glow by animateFloatAsState(if (running) 0.3f else 0f, tween(500), label = "glow")
    val c1 by animateColorAsState(if (running) Accent else Color(0xFF2A2A3A), tween(500), label = "c1")
    val c2 by animateColorAsState(if (running) Accent2 else Color(0xFF3A3A4E), tween(500), label = "c2")
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(120.dp)
                .scale(if (running) pulse else 1f)
                .clip(CircleShape)
                .background(Accent.copy(alpha = glow))
        )
        Box(
            Modifier
                .size(104.dp)
                .scale(if (enabled) 1f else 0.95f)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2)))
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) "STOP" else "START",
                color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun PermRow(text: String, granted: Boolean, onGrant: () -> Unit) {
    val dot by animateColorAsState(if (granted) Good else Color(0xFFFF8A80), label = "dot")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (granted) {
            Text("✓", color = Good, fontSize = 18.sp)
        } else {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Выдать") }
        }
    }
}

@Composable
private fun ColorPicker(color: Int, onChange: (Int) -> Unit) {
    val swatches = remember {
        listOf(
            0xFFFFFFFF, 0xFFFFE066, 0xFFFF9F43, 0xFFFF6B9D, 0xFFFF4D4D,
            0xFFB388FF, 0xFF5CC8FF, 0xFF3DF5E0, 0xFF7CFF6B
        ).map { it.toInt() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            swatches.forEach { c ->
                val sel = c == color
                val bw by animateDpAsState(if (sel) 3.dp else 0.dp, label = "bw")
                val sc by animateFloatAsState(if (sel) 1.15f else 1f, label = "sc")
                Box(
                    Modifier
                        .size(34.dp)
                        .scale(sc)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(bw, Color.White, CircleShape)
                        .clickable { onChange(c) }
                )
            }
        }
        val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
        val rainbow = remember { Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 0.8f, 1f) }) }
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(rainbow)
            )
            Slider(
                value = hsv[0],
                onValueChange = { h -> onChange(android.graphics.Color.HSVToColor(floatArrayOf(h, 0.8f, 1f))) },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
}

// ---------------------------------------------------------------- main screen

@Composable
fun MainScreen(perms: PermState, onPickFont: () -> Unit, onResetFont: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.config.collectAsState()
    val now by NowPlaying.state.collectAsState()
    val running by OverlayService.running.collectAsState()
    val lyr by LyricsManager.state.collectAsState()
    val allOk = perms.overlay && perms.listener

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1636), Bg)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // header
            EnterItem(0) {
                Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
                    Text(
                        "Lyrix",
                        style = TextStyle(
                            brush = Brush.linearGradient(listOf(Accent, Accent2)),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black
                        )
                    )
                    Text("Текст песни поверх экрана", color = Muted, fontSize = 14.sp)
                }
            }

            // power
            EnterItem(1) {
                AppCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PowerButton(running = running, enabled = allOk) {
                            if (running) OverlayService.stop(ctx) else OverlayService.start(ctx)
                        }
                        Crossfade(
                            targetState = when {
                                !allOk -> "Сначала выдай разрешения"
                                running -> "Оверлей включён"
                                else -> "Нажми, чтобы включить"
                            },
                            label = "powerText"
                        ) { t -> Text(t, color = if (running) Good else Muted, fontSize = 14.sp) }
                    }
                }
            }

            // permissions
            EnterItem(2) {
                AnimatedVisibility(
                    visible = !allOk,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    AppCard {
                        SectionTitle("Разрешения")
                        PermRow("Показ поверх других приложений", perms.overlay) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                        PermRow("Доступ к уведомлениям (видеть, что играет)", perms.listener) {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        Text(
                            "Если переключатель серый: Настройки → Приложения → Lyrix → ⋮ → «Разрешить ограниченные настройки».",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // now playing
            EnterItem(3) {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Crossfade(targetState = now.track?.art, label = "cover") { bmp: Bitmap? ->
                            Box(
                                Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF2A2A3A))
                            ) {
                                if (bmp != null) {
                                    val img = remember(bmp) { bmp.asImageBitmap() }
                                    Image(
                                        bitmap = img,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                now.track?.title ?: "Ничего не играет",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                now.track?.artist ?: "Запусти музыку в любом плеере",
                                color = Muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val status: Pair<String, Color> = when {
                                now.track == null -> "" to Muted
                                !running -> "Включи оверлей, чтобы искать текст" to Muted
                                else -> when (lyr) {
                                    LyricsState.Loading -> "Ищу текст…" to Accent2
                                    is LyricsState.Found -> "Текст найден" to Good
                                    LyricsState.NotFound -> "Текст не найден" to Color(0xFFFF8A80)
                                    LyricsState.Idle -> "" to Muted
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (running && lyr == LyricsState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = Accent2
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Crossfade(targetState = status, label = "status") { (txt, col) ->
                                    Text(txt, color = col, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // text settings
            EnterItem(4) {
                AppCard {
                    SectionTitle("Текст")

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !cfg.colorFromCover,
                            onClick = { Prefs.update { it.copy(colorFromCover = false) } },
                            label = { Text("Свой цвет") }
                        )
                        FilterChip(
                            selected = cfg.colorFromCover,
                            onClick = { Prefs.update { it.copy(colorFromCover = true) } },
                            label = { Text("Цвет обложки") }
                        )
                    }
                    AnimatedVisibility(
                        visible = !cfg.colorFromCover,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        ColorPicker(cfg.textColor) { c -> Prefs.update { it.copy(textColor = c) } }
                    }

                    // preview
                    val art = now.track?.art
                    val coverCol = remember(art) { art?.let { CoverColor.pick(it) } ?: android.graphics.Color.WHITE }
                    val previewColor by animateColorAsState(
                        Color(if (cfg.colorFromCover) coverCol else cfg.textColor),
                        tween(400),
                        label = "previewColor"
                    )
                    val family = remember(cfg.fontPath) {
                        cfg.fontPath?.let { p -> runCatching { FontFamily(Typeface.createFromFile(p)) }.getOrNull() }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0E0E14))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Пример текста · Sample 123",
                            color = previewColor,
                            fontFamily = family,
                            fontWeight = FontWeight.Bold,
                            fontSize = (cfg.textSizeSp * 0.7f).sp
                        )
                    }

                    LabeledSlider("Размер", "${cfg.textSizeSp.toInt()} sp", cfg.textSizeSp, 16f..48f) { v ->
                        Prefs.update { it.copy(textSizeSp = v) }
                    }
                    LabeledSlider("Положение по высоте", "${(cfg.verticalPos * 100).toInt()}%", cfg.verticalPos, 0.08f..0.95f) { v ->
                        Prefs.update { it.copy(verticalPos = v) }
                    }
                    LabeledSlider(
                        "Синхронизация",
                        String.format(Locale.US, "%+.1f с", cfg.offsetMs / 1000f),
                        cfg.offsetMs.toFloat(),
                        -3000f..3000f,
                        steps = 59
                    ) { v -> Prefs.update { it.copy(offsetMs = v.toInt()) } }

                    Text("Анимация появления", color = Color.White, fontSize = 14.sp)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Подъём", "Зум", "Fade", "Pop").forEachIndexed { i, name ->
                            FilterChip(
                                selected = cfg.animStyle == i,
                                onClick = { Prefs.update { it.copy(animStyle = i) } },
                                label = { Text(name) }
                            )
                        }
                    }

                    SwitchRow("Следующая строка", "Показывать приглушённо под текущей", cfg.showNext) { v ->
                        Prefs.update { it.copy(showNext = v) }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Шрифт", color = Color.White, fontSize = 15.sp)
                            Text(cfg.fontName ?: "По умолчанию", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (cfg.fontPath != null) TextButton(onClick = onResetFont) { Text("Сброс") }
                        Button(onClick = onPickFont, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("Выбрать .ttf")
                        }
                    }
                }
            }

            // zones
            EnterItem(5) {
                AppCard {
                    SectionTitle("Зоны без текста")
                    SwitchRow("Скрыть текст по центру", "Полоса посреди экрана без текста", cfg.hideCenter) { v ->
                        Prefs.update { it.copy(hideCenter = v) }
                    }
                    AnimatedVisibility(
                        visible = cfg.hideCenter,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        LabeledSlider("Высота полосы", "${(cfg.centerSize * 100).toInt()}%", cfg.centerSize, 0.1f..0.7f) { v ->
                            Prefs.update { it.copy(centerSize = v) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Рамок: ${cfg.frames.size}", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (cfg.frames.isNotEmpty()) {
                            TextButton(onClick = { Prefs.update { it.copy(frames = emptyList()) } }) { Text("Очистить") }
                        }
                        Button(
                            onClick = {
                                if (allOk) OverlayService.start(ctx, edit = true)
                                else Toast.makeText(ctx, "Сначала выдай разрешения", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("Редактировать") }
                    }
                    Text(
                        "Совет: открой нужное приложение, потяни шторку и нажми «Рамки» в уведомлении Lyrix — так видно, куда ставить рамки.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }

            // player overlay
            EnterItem(6) {
                AppCard {
                    SectionTitle("Плеер")
                    SwitchRow(
                        "Оверлей с обложкой",
                        "Обложка, название, назад / пауза / вперёд. Двигается пальцем.",
                        cfg.playerCard
                    ) { v -> Prefs.update { it.copy(playerCard = v) } }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Тексты: lrclib.net",
                color = Muted.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
