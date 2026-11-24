package com.lyrix.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AnimNames = listOf("Fade", "Подъём", "Спуск", "Слева", "Справа", "Зум +", "Зум −", "Pop", "Поворот", "Печать", "Свечение", "Раскрытие")
val StyleNames = listOf("Классика", "Караоке", "Поп-ап", "Колесо")
val ColorModeNames = listOf("Свой", "Обложка", "Контраст")
val SplitValues = listOf(0, 2, 3, 4, 5)
val SplitNames = listOf("Целиком", "2 слова", "3 слова", "4 слова", "5 слов")

private val TextSwatches = listOf(
    0xFFFFFFFF, 0xFFFFE066, 0xFFFF9F43, 0xFFFF6B9D, 0xFFFF4D4D,
    0xFFB388FF, 0xFF5CC8FF, 0xFF3DF5E0, 0xFF7CFF6B
).map { it.toInt() }

val BackdropSwatches = listOf(
    0xFF000000, 0xFF0D0B2A, 0xFF2B1B5A, 0xFF10303A, 0xFF3A1030, 0xFFFFFFFF, 0xFF9B7BFF
).map { it.toInt() }

/** Переключатель с плавающим индикатором. */
@Composable
fun SegTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x22FFFFFF))
    ) {
        val itemW = maxWidth / items.size
        val x by animateDpAsState(itemW * selected, spring(dampingRatio = 0.65f, stiffness = 380f), label = "segX")
        Box(
            Modifier
                .offset(x = x)
                .width(itemW)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(Sp.Violet, Sp.Cyan)))
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, s ->
                val col by animateColorAsState(if (i == selected) Sp.Bg0 else Color.White, tween(220), label = "segText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) { Text(s, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
fun ColorPicker(color: Int, swatches: List<Int> = TextSwatches, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            swatches.forEach { c ->
                val sel = c == color
                val bw by animateDpAsState(if (sel) 3.dp else 1.dp, label = "bw")
                val sc by animateFloatAsState(if (sel) 1.15f else 1f, spring(dampingRatio = 0.5f), label = "sc")
                Box(
                    Modifier
                        .size(34.dp)
                        .scale(sc)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(bw, if (sel) Color.White else Color(0x55FFFFFF), CircleShape)
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

private fun resolvePreview(mode: Int, custom: Int, cover: Int): Int = when (mode) {
    1 -> cover
    2 -> CoverColor.contrastOf(cover)
    else -> custom
}

@Composable
fun TextColorSection(cfg: Config, coverColor: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Цвет текста", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        SegTabs(ColorModeNames, cfg.colorMode) { m -> Prefs.update { it.copy(colorMode = m) } }
        AnimatedVisibility(cfg.colorMode == 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ColorPicker(cfg.textColor) { c -> Prefs.update { it.copy(textColor = c) } }
        }
        val prev by animateColorAsState(Color(resolvePreview(cfg.colorMode, cfg.textColor, coverColor)), tween(400), label = "prev")
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x66000000)).padding(12.dp),
            contentAlignment = Alignment.Center
        ) { Text("Пример текста · Sample", color = prev, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}

@Composable
private fun CommonSettings(cfg: Config, position: Boolean, animChips: Boolean) {
    LabeledSlider("Размер", "${cfg.textSizeSp.toInt()} sp", cfg.textSizeSp, 16f..52f) { v ->
        Prefs.update { it.copy(textSizeSp = v) }
    }
    if (position) {
        LabeledSlider("Положение по высоте", "${(cfg.verticalPos * 100).toInt()}%", cfg.verticalPos, 0.08f..0.95f) { v ->
            Prefs.update { it.copy(verticalPos = v) }
        }
    }
    if (animChips) {
        Text("Анимация появления", color = Color.White, fontSize = 14.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimNames.forEachIndexed { i, n -> SpaceChip(n, cfg.anim == i) { Prefs.update { it.copy(anim = i) } } }
        }
    }
    LabeledSlider(
        if (animChips) "Длительность анимации" else "Скорость прокрутки",
        "${cfg.animMs} мс", cfg.animMs.toFloat(), 250f..1400f
    ) { v -> Prefs.update { it.copy(animMs = v.toInt()) } }
}

/** Настройки выбранного стиля — раскрываются под переключателем стилей. */
@Composable
fun StyleSettings(cfg: Config) {
    AnimatedContent(
        targetState = cfg.subStyle,
        transitionSpec = {
            (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 10 }) togetherWith fadeOut(tween(140))
        },
        label = "styleSettings"
    ) { st ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (st) {
                0 -> {
                    CommonSettings(cfg, position = true, animChips = true)
                    SwitchRow("Следующая строка", "Приглушённо под текущей", cfg.showNext) { v -> Prefs.update { it.copy(showNext = v) } }
                }
                1 -> {
                    CommonSettings(cfg, position = true, animChips = true)
                    SwitchRow("Следующая строка", "Приглушённо под текущей", cfg.showNext) { v -> Prefs.update { it.copy(showNext = v) } }
                    Text("Цвет подсвеченных слов", color = Color.White, fontSize = 14.sp)
                    SegTabs(ColorModeNames, cfg.hiMode) { m -> Prefs.update { it.copy(hiMode = m) } }
                    AnimatedVisibility(cfg.hiMode == 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        ColorPicker(cfg.hiColor) { c -> Prefs.update { it.copy(hiColor = c) } }
                    }
                    LabeledSlider("Яркость непропетых слов", "${(cfg.kDim * 100).toInt()}%", cfg.kDim, 0.2f..1f) { v ->
                        Prefs.update { it.copy(kDim = v) }
                    }
                    SwitchRow("По словам", "Слово загорается целиком, а не плавно", cfg.kWordSnap) { v ->
                        Prefs.update { it.copy(kWordSnap = v) }
                    }
                    HintText("Слова распределяются по времени строки — у lrclib нет побуквенных таймкодов.")
                }
                2 -> {
                    CommonSettings(cfg, position = false, animChips = true)
                    SwitchRow("Лёгкий наклон", "Каждая строка чуть под углом", cfg.popupTilt) { v -> Prefs.update { it.copy(popupTilt = v) } }
                    HintText("Место появления случайное; рамки и центральная полоса обходятся.")
                }
                else -> {
                    CommonSettings(cfg, position = true, animChips = false)
                    Text("Строк в колесе", color = Color.White, fontSize = 14.sp)
                    SegTabs(listOf("3", "5", "7"), listOf(3, 5, 7).indexOf(cfg.wheelRows).coerceAtLeast(0)) { i ->
                        Prefs.update { it.copy(wheelRows = listOf(3, 5, 7)[i]) }
                    }
                    LabeledSlider("Сжатие дальних строк", "${(cfg.wheelSpread * 100).toInt()}%", cfg.wheelSpread, 0f..0.3f) { v ->
                        Prefs.update { it.copy(wheelSpread = v) }
                    }
                }
            }
        }
    }
}

/** Части строки, прозрачность текста, подложка. */
@Composable
fun GeneralTextSettings(cfg: Config) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Показывать по частям", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitValues.forEachIndexed { i, v ->
                SpaceChip(SplitNames[i], cfg.splitWords == v) { Prefs.update { it.copy(splitWords = v) } }
            }
        }
        HintText("Длинная строка делится на куски по N слов, каждый появляется в свой момент.")
        LabeledSlider("Прозрачность текста", "${(cfg.textAlpha * 100).toInt()}%", cfg.textAlpha, 0.1f..1f) { v ->
            Prefs.update { it.copy(textAlpha = v) }
        }
        SwitchRow("Подложка", "Плашка под текстом", cfg.bdShow) { v -> Prefs.update { it.copy(bdShow = v) } }
        AnimatedVisibility(cfg.bdShow, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegTabs(listOf("Цвет", "Цвет обложки", "Размытая"), cfg.bdMode) { m -> Prefs.update { it.copy(bdMode = m) } }
                AnimatedVisibility(cfg.bdMode == 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    ColorPicker(cfg.bdColor, BackdropSwatches) { c -> Prefs.update { it.copy(bdColor = c) } }
                }
                LabeledSlider("Прозрачность подложки", "${(cfg.bdAlpha * 100).toInt()}%", cfg.bdAlpha, 0f..1f) { v ->
                    Prefs.update { it.copy(bdAlpha = v) }
                }
            }
        }
    }
}

/** Крутящаяся обложка-пластинка со свечением. */
@Composable
fun CoverHero(track: Track?, playing: Boolean, accent: Int, size: Dp = 150.dp) {
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                angle = (angle + (now - last) / 1_000_000_000f * 24f) % 360f
                last = now
            }
        }
    }
    val pulse by rememberInfiniteTransition(label = "heroPulse").animateFloat(
        0.9f, 1.08f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "p"
    )
    Box(Modifier.size(size + 40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size + 36.dp)
                .scale(if (playing) pulse else 1f)
                .background(Brush.radialGradient(listOf(Color(accent).copy(alpha = 0.45f), Color.Transparent)), CircleShape)
        )
        Crossfade(track?.art, animationSpec = tween(500), label = "heroArt") { bmp ->
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer { rotationZ = angle }
                    .clip(CircleShape)
                    .background(Color(0xFF1C1A3D))
                    .border(2.dp, Brush.sweepGradient(listOf(Sp.Violet, Sp.Cyan, Sp.Pink, Sp.Violet)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", fontSize = 48.sp, color = Sp.Muted)
                }
                Box(Modifier.size(18.dp).clip(CircleShape).background(Sp.Bg0))
            }
        }
    }
}

/** Кнопки назад / пауза / вперёд. */
@Composable
fun TransportRow(playing: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(R.drawable.ic_prev, 48.dp, false) { NowPlaying.prev() }
        Crossfade(playing, animationSpec = tween(220), label = "playIcon") { p ->
            RoundIcon(if (p) R.drawable.ic_pause else R.drawable.ic_play, 62.dp, true) { NowPlaying.playPause() }
        }
        RoundIcon(R.drawable.ic_next, 48.dp, false) { NowPlaying.next() }
    }
}

@Composable
private fun RoundIcon(res: Int, size: Dp, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .bounceClick(onClick)
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(Brush.linearGradient(listOf(Sp.Violet, Sp.Cyan)))
                else Modifier.background(Color(0x22FFFFFF))
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painterResource(res), null,
            colorFilter = ColorFilter.tint(if (filled) Sp.Bg0 else Color.White),
            modifier = Modifier.size(size * 0.5f)
        )
    }
}
