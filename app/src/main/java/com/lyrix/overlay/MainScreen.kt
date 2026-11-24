package com.lyrix.overlay

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

data class PermState(val overlay: Boolean = false, val listener: Boolean = false)

class MainActions(
    val pickFont: () -> Unit,
    val resetFont: () -> Unit,
    val pickBubbleIcon: () -> Unit,
    val pickShortcutIcon: () -> Unit,
    val exportCfg: () -> Unit,
    val importCfg: () -> Unit
)

fun lyricsStatus(hasTrack: Boolean, running: Boolean, lyr: LyricsState, offline: Boolean): Pair<String, Color> = when {
    !hasTrack -> "Включи музыку в любом плеере" to Sp.Muted
    !running -> "Запусти оверлей, чтобы искать текст" to Sp.Muted
    else -> when (lyr) {
        LyricsState.Loading -> (if (offline) "Ищу в избранном и кэше…" else "Ищу текст…") to Sp.Cyan
        is LyricsState.Found -> "Текст найден" to Sp.Good
        LyricsState.NotFound -> (if (offline) "Офлайн: этого трека нет в избранном" else "Текст не найден") to Sp.Bad
        LyricsState.Idle -> "" to Sp.Muted
    }
}

@Composable
fun MainScreen(perms: PermState, actions: MainActions) {
    var showDev by remember { mutableStateOf(false) }
    val cfg by Prefs.config.collectAsState()
    BackHandler(showDev) { showDev = false }
    SpaceBackground {
        Crossfade(showDev && cfg.devUnlocked, animationSpec = tween(350), label = "screen") { dev ->
            if (dev) DevScreen(perms) { showDev = false }
            else MainContent(perms, actions) { showDev = true }
        }
    }
}

@Composable
private fun PowerButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulse by inf.animateFloat(
        1f, 1.22f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseValue"
    )
    val glow by animateFloatAsState(if (running) 0.35f else 0f, tween(500), label = "glow")
    val c1 by animateColorAsState(if (running) Sp.Violet else Color(0xFF2A2A44), tween(500), label = "c1")
    val c2 by animateColorAsState(if (running) Sp.Cyan else Color(0xFF3A3A5E), tween(500), label = "c2")
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(130.dp).scale(if (running) pulse else 1f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Sp.Cyan.copy(alpha = glow), Color.Transparent)))
        )
        Box(
            Modifier
                .size(108.dp)
                .then(if (enabled) Modifier.bounceClick(onClick) else Modifier)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2)))
                .border(2.dp, Brush.sweepGradient(listOf(Sp.Violet, Sp.Cyan, Sp.Pink, Sp.Violet)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) "STOP" else "START",
                color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun PermRow(text: String, granted: Boolean, onGrant: () -> Unit) {
    val dot by animateColorAsState(if (granted) Sp.Good else Sp.Bad, label = "dot")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (granted) Text("✓", color = Sp.Good, fontSize = 18.sp) else SpaceButton("Выдать", onClick = onGrant)
    }
}

@Composable
private fun IconTile(
    selected: Boolean,
    bg: Color,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val bw by animateDpAsState(if (selected) 3.dp else 1.dp, label = "tileBw")
    val sc by animateFloatAsState(if (selected) 1.08f else 1f, spring(dampingRatio = 0.5f), label = "tileSc")
    Box(
        Modifier
            .size(54.dp)
            .scale(sc)
            .bounceClick(onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(bw, if (selected) Color.White else Color(0x33FFFFFF), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun FavRow(f: Fav) {
    val ctx = LocalContext.current
    val cover = remember(f.id, Favorites.coverFile(f.id).lastModified()) {
        val file = Favorites.coverFile(f.id)
        if (file.exists()) BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) else null
    }
    val hasLyrics = Favorites.lyricsFile(f.id).exists()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A44)), contentAlignment = Alignment.Center) {
            if (cover != null) {
                Image(cover.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else Text("♪", color = Sp.Muted)
        }
        Column(Modifier.weight(1f)) {
            Text(f.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.artist, color = Sp.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "обложка ${if (cover != null) "✓" else "—"} · текст ${if (hasLyrics) "✓" else "—"}",
                color = if (cover != null && hasLyrics) Sp.Good else Sp.Muted, fontSize = 11.sp
            )
        }
        Box(
            Modifier.size(40.dp).bounceClick { FavLauncher.play(ctx, f) }.clip(CircleShape)
                .background(Brush.linearGradient(listOf(Sp.Violet, Sp.Cyan))),
            contentAlignment = Alignment.Center
        ) { Text("▶", color = Sp.Bg0, fontSize = 14.sp) }
        Box(
            Modifier.size(36.dp).bounceClick { Favorites.removeById(f.id) }.clip(CircleShape).background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center
        ) { Text("✕", color = Color.White, fontSize = 13.sp) }
    }
}

@Composable
private fun VersionFooter(devUnlocked: Boolean) {
    val ctx = LocalContext.current
    var taps by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(taps) {
        if (taps > 0) {
            delay(2500)
            taps = 0
        }
    }
    Text(
        "Lyrix v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        color = Sp.Muted.copy(alpha = 0.7f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                taps++
                toast?.cancel()
                if (devUnlocked) {
                    toast = Toast.makeText(ctx, "Меню разработчика уже включено", Toast.LENGTH_SHORT).also { it.show() }
                    taps = 0
                } else if (taps >= 10) {
                    Prefs.update { it.copy(devUnlocked = true) }
                    toast = Toast.makeText(ctx, "Меню разработчика включено", Toast.LENGTH_LONG).also { it.show() }
                    taps = 0
                } else if (taps >= 4) {
                    toast = Toast.makeText(ctx, "До меню разработчика: ${10 - taps}", Toast.LENGTH_SHORT).also { it.show() }
                }
            }
            .padding(vertical = 14.dp)
    )
}

@Composable
private fun MainContent(perms: PermState, actions: MainActions, openDev: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.config.collectAsState()
    val now by NowPlaying.state.collectAsState()
    val running by OverlayService.running.collectAsState()
    val lyr by LyricsManager.state.collectAsState()
    val favs by Favorites.list.collectAsState()
    val allOk = perms.overlay && perms.listener
    val track = now.track
    val art = track?.art
    val coverCol = remember(art) { art?.let { CoverColor.pick(it) } ?: android.graphics.Color.WHITE }
    val isFav = track != null && favs.any { it.key == track.key }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        EnterItem(0) {
            Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
                GradientTitle("Lyrix")
                Text("Текст песни поверх экрана ✦", color = Sp.Muted, fontSize = 14.sp)
            }
        }

        EnterItem(1) {
            GlassCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PowerButton(running, allOk) { if (running) OverlayService.stop(ctx) else OverlayService.start(ctx) }
                    Crossfade(
                        when {
                            !allOk -> "Сначала выдай разрешения"
                            running -> "Оверлей включён"
                            else -> "Нажми, чтобы включить"
                        },
                        label = "powerText"
                    ) { t -> Text(t, color = if (running) Sp.Good else Sp.Muted, fontSize = 14.sp) }
                }
            }
        }

        EnterItem(2) {
            AnimatedVisibility(!allOk, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                GlassCard {
                    SectionTitle("Разрешения")
                    PermRow("Показ поверх других приложений", perms.overlay) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                    PermRow("Доступ к уведомлениям (видеть, что играет)", perms.listener) {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    HintText("Если переключатель серый: Настройки → Приложения → Lyrix → ⋮ → «Разрешить ограниченные настройки».")
                }
            }
        }

        // сейчас играет
        EnterItem(3) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Crossfade(art, animationSpec = tween(500), label = "cover") { bmp ->
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2A2A44))) {
                            if (bmp != null) {
                                val img = remember(bmp) { bmp.asImageBitmap() }
                                Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            track?.title ?: "Ничего не играет", color = Color.White, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            track?.artist ?: "Запусти музыку в любом плеере", color = Sp.Muted, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val status = lyricsStatus(track != null, running, lyr, cfg.offlineMode)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (running && lyr == LyricsState.Loading && !cfg.offlineMode) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Sp.Cyan)
                                Spacer(Modifier.width(6.dp))
                            }
                            Crossfade(status, label = "status") { (txt, col) -> Text(txt, color = col, fontSize = 12.sp) }
                        }
                    }
                    if (track != null) {
                        val heartScale by animateFloatAsState(if (isFav) 1.15f else 1f, spring(dampingRatio = 0.35f), label = "heart")
                        Box(
                            Modifier.size(44.dp).bounceClick { Favorites.toggle(track) }.scale(heartScale),
                            contentAlignment = Alignment.Center
                        ) { Text(if (isFav) "♥" else "♡", color = if (isFav) Sp.Pink else Sp.Muted, fontSize = 26.sp) }
                    }
                }
            }
        }

        // субтитры
        EnterItem(4) {
            GlassCard {
                SectionTitle("Субтитры")
                SegTabs(StyleNames, cfg.subStyle) { s -> Prefs.update { it.copy(subStyle = s) } }
                StyleSettings(cfg)
                GeneralTextSettings(cfg)
                TextColorSection(cfg, coverCol)
            }
        }

        // шрифт и синхронизация
        EnterItem(5) {
            GlassCard {
                SectionTitle("Шрифт и синхронизация")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Шрифт", color = Color.White, fontSize = 15.sp)
                        Text(cfg.fontName ?: "По умолчанию", color = Sp.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (cfg.fontPath != null) SpaceButton("Сброс", filled = false, onClick = actions.resetFont)
                    SpaceButton("Выбрать", onClick = actions.pickFont)
                }
                LabeledSlider(
                    "Сдвиг текста по времени",
                    String.format(java.util.Locale.US, "%+.1f с", cfg.offsetMs / 1000f),
                    cfg.offsetMs.toFloat(), -3000f..3000f, steps = 59
                ) { v -> Prefs.update { it.copy(offsetMs = v.toInt()) } }
                HintText("Пригодится для ремиксов: если текст идёт раньше или позже музыки — подвинь.")
            }
        }

        // офлайн
        EnterItem(6) {
            GlassCard {
                SectionTitle("Тексты и обложки")
                SwitchRow(
                    "Офлайн-режим",
                    "Ничего не ищет в интернете — только избранное, кэш и свои тексты",
                    cfg.offlineMode
                ) { v -> Prefs.update { it.copy(offlineMode = v) } }
                var cacheInfo by remember { mutableStateOf(LyricsManager.cacheInfo()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Кэш текстов", color = Color.White, fontSize = 15.sp)
                        Text(cacheInfo, color = Sp.Muted, fontSize = 12.sp)
                    }
                    SpaceButton("Очистить", filled = false) {
                        LyricsManager.clearCache()
                        cacheInfo = LyricsManager.cacheInfo()
                    }
                }
                HintText("Для трека без текста (оффлайн-файл, ремикс) открой меню по кнопке-оверлею → «Свой текст (.lrc)».")
            }
        }

        // избранное
        EnterItem(7) {
            GlassCard {
                SectionTitle("Избранное")
                if (favs.isEmpty()) {
                    HintText("Нажми ♡ у играющего трека — обложка и текст сохранятся в приложении и будут открываться мгновенно и без интернета.")
                } else {
                    favs.forEach { FavRow(it) }
                    HintText("▶ запускает трек в том плеере, откуда он был добавлен (если плеер поддерживает поиск по команде).")
                }
            }
        }

        // зоны
        EnterItem(8) {
            GlassCard {
                SectionTitle("Зоны без текста")
                SwitchRow("Скрыть текст по центру", "Полоса посреди экрана без текста", cfg.hideCenter) { v ->
                    Prefs.update { it.copy(hideCenter = v) }
                }
                AnimatedVisibility(cfg.hideCenter, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    LabeledSlider("Высота полосы", "${(cfg.centerSize * 100).toInt()}%", cfg.centerSize, 0.1f..0.7f) { v ->
                        Prefs.update { it.copy(centerSize = v) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Рамок: ${cfg.frames.size}", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (cfg.frames.isNotEmpty()) SpaceButton("Очистить", filled = false) { Prefs.update { it.copy(frames = emptyList()) } }
                    SpaceButton("Редактировать") {
                        if (allOk) OverlayService.start(ctx, edit = true)
                        else Toast.makeText(ctx, "Сначала выдай разрешения", Toast.LENGTH_SHORT).show()
                    }
                }
                HintText("Совет: открой нужное приложение, потяни шторку и нажми «Рамки» в уведомлении Lyrix.")
            }
        }

        // кнопка-оверлей
        EnterItem(9) {
            GlassCard {
                SectionTitle("Кнопка-оверлей")
                SwitchRow("Показывать кнопку", "Тап открывает меню, перетаскивание двигает", cfg.bubbleShow) { v ->
                    Prefs.update { it.copy(bubbleShow = v) }
                }
                AnimatedVisibility(cfg.bubbleShow, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SegTabs(listOf("Иконка", "Обложка"), cfg.bubbleMode) { m -> Prefs.update { it.copy(bubbleMode = m) } }
                        Text("Иконка", color = Color.White, fontSize = 14.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BubbleIcons.all.forEach { n ->
                                IconTile(cfg.bubbleIcon == n, Color(0xFF1C1A3D), { Prefs.update { it.copy(bubbleIcon = n) } }) {
                                    Image(painterResource(BubbleIcons.res(n)), null, colorFilter = ColorFilter.tint(Sp.Cyan), modifier = Modifier.size(28.dp))
                                }
                            }
                            val custom = remember(cfg.bubbleIconPath) { cfg.bubbleIconPath?.let { BitmapFactory.decodeFile(it) } }
                            IconTile(cfg.bubbleIcon == "custom", Color(0xFF1C1A3D), actions.pickBubbleIcon) {
                                if (custom != null) Image(custom.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Text("+", color = Sp.Cyan, fontSize = 24.sp)
                            }
                        }
                        HintText("Последняя плитка «+» — своя картинка. В режиме «Обложка» вместо иконки показывается обложка трека (вращается как пластинка).")
                        LabeledSlider("Размер", "${cfg.bubbleSize} dp", cfg.bubbleSize.toFloat(), 40f..100f) { v ->
                            Prefs.update { it.copy(bubbleSize = v.toInt()) }
                        }
                        SwitchRow("Открывать меню по тапу", "Выключи, если кнопка нужна только как украшение", cfg.bubbleOpensMenu) { v ->
                            Prefs.update { it.copy(bubbleOpensMenu = v) }
                        }
                        SwitchRow("Вращать обложку", "Пока играет музыка", cfg.bubbleSpin) { v -> Prefs.update { it.copy(bubbleSpin = v) } }
                    }
                }
            }
        }

        // значок приложения
        EnterItem(10) {
            GlassCard {
                SectionTitle("Значок приложения")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppIcons.names.forEach { n ->
                        IconTile(cfg.appIcon == n, Color(AppIcons.bg(n)), {
                            AppIcons.apply(ctx, n)
                            Prefs.update { it.copy(appIcon = n) }
                            Toast.makeText(ctx, "Значок сменится через пару секунд", Toast.LENGTH_SHORT).show()
                        }) {
                            Image(painterResource(AppIcons.preview(n)), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(30.dp))
                        }
                    }
                }
                SpaceButton("Свой значок (ярлык на рабочем столе)", filled = false, onClick = actions.pickShortcutIcon)
                HintText("Android не позволяет подменить иконку приложения произвольной картинкой, поэтому свой значок создаётся как ярлык на рабочем столе.")
            }
        }

        // резервная копия
        EnterItem(11) {
            GlassCard {
                SectionTitle("Резервная копия настроек")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpaceButton("Сохранить в файл", Modifier.weight(1f), onClick = actions.exportCfg)
                    SpaceButton("Загрузить из файла", Modifier.weight(1f), filled = false, onClick = actions.importCfg)
                }
                HintText("В файл попадают стили, цвета, подложка, рамки и т.д. Шрифт и свои картинки не сохраняются.")
            }
        }

        if (cfg.devUnlocked) {
            EnterItem(12) {
                GlassCard(Modifier.bounceClick(openDev)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Меню разработчика", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("›", color = Sp.Cyan, fontSize = 22.sp)
                    }
                }
            }
        }

        VersionFooter(cfg.devUnlocked)
        Spacer(Modifier.height(8.dp))
    }
}
