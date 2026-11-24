package com.lyrix.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

/**
 * Меню по тапу на кнопку-оверлей. Прозрачная Activity: панель сверху, внизу остаётся видно текст — настройки видно сразу.
 */
class MenuActivity : ComponentActivity() {

    companion object {
        @Volatile
        var instance: MenuActivity? = null
    }

    private val closeFlag = mutableStateOf(false)
    private val lrcPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importLrc(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SpaceTheme {
                MenuScreen(
                    closeFlag = closeFlag,
                    onClose = { close() },
                    onFinish = { finish() },
                    onPickLrc = {
                        if (NowPlaying.state.value.track == null) {
                            Toast.makeText(this, "Сначала включи трек", Toast.LENGTH_SHORT).show()
                        } else lrcPicker.launch(arrayOf("*/*"))
                    }
                )
            }
        }
    }

    fun close() {
        closeFlag.value = true
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun importLrc(uri: Uri) {
        val t = NowPlaying.state.value.track ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            if (LocalStore.importText(t.key, text, t.durationMs)) {
                LyricsManager.reload()
                Toast.makeText(this, "Текст загружен для этого трека", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "В файле нет текста", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MenuScreen(closeFlag: State<Boolean>, onClose: () -> Unit, onFinish: () -> Unit, onPickLrc: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.config.collectAsState()
    val now by NowPlaying.state.collectAsState()
    val lyr by LyricsManager.state.collectAsState()
    val favs by Favorites.list.collectAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }
    LaunchedEffect(closeFlag.value) {
        if (closeFlag.value) {
            show = false
            delay(320)
            onFinish()
        }
    }
    BackHandler { onClose() }

    val track = now.track
    val art = track?.art
    val coverCol = remember(art) { art?.let { CoverColor.pick(it) } ?: android.graphics.Color.WHITE }
    val isFav = track != null && favs.any { it.key == track.key }
    val hasCustom = remember(track?.key, lyr) { track != null && LocalStore.hasCustom(track.key) }
    val status = lyricsStatus(track != null, true, lyr, cfg.offlineMode)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(show, enter = fadeIn(tween(300)), exit = fadeOut(tween(250))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
            )
        }
        AnimatedVisibility(
            show,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { -it } + fadeIn(tween(250)),
            exit = slideOutVertically(tween(260)) { -it } + fadeOut(tween(200))
        ) {
            val shape = RoundedCornerShape(28.dp)
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(10.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xF00D0B2A), Color(0xF005030F))))
                    .border(1.dp, Brush.linearGradient(listOf(Sp.Violet.copy(0.6f), Sp.Cyan.copy(0.3f), Sp.Pink.copy(0.4f))), shape)
            ) {
                StarField(Modifier.matchParentSize())
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { GradientTitle("Lyrix", 22) }
                        Box(
                            Modifier.size(36.dp).bounceClick(onClose).clip(CircleShape).background(Color(0x22FFFFFF)),
                            contentAlignment = Alignment.Center
                        ) { Text("✕", color = Color.White, fontSize = 15.sp) }
                    }

                    EnterItem(0) { CoverHero(track, now.playing, coverCol) }

                    EnterItem(1) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                track?.title ?: "Ничего не играет",
                                color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                            )
                            Text(
                                track?.artist ?: "",
                                color = Sp.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Crossfade(status, label = "menuStatus") { (txt, col) -> Text(txt, color = col, fontSize = 12.sp) }
                        }
                    }

                    EnterItem(2) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SpaceChip(if (isFav) "♥ В избранном" else "♡ В избранное", isFav) {
                                if (track != null) Favorites.toggle(track)
                                else Toast.makeText(ctx, "Сначала включи трек", Toast.LENGTH_SHORT).show()
                            }
                            SpaceChip("Офлайн-режим", cfg.offlineMode) { Prefs.update { it.copy(offlineMode = !it.offlineMode) } }
                            SpaceChip("Свой текст (.lrc)", false, onPickLrc)
                            if (hasCustom) SpaceChip("Сбросить свой текст", false) {
                                track?.let { LocalStore.clear(it.key) }
                                LyricsManager.reload()
                            }
                        }
                    }

                    EnterItem(3) { TransportRow(now.playing) }

                    EnterItem(4) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Стиль субтитров", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            SegTabs(StyleNames, cfg.subStyle) { s -> Prefs.update { it.copy(subStyle = s) } }
                            StyleSettings(cfg)
                        }
                    }
                    EnterItem(5) { GeneralTextSettings(cfg) }
                    EnterItem(6) { TextColorSection(cfg, coverCol) }
                    EnterItem(7) {
                        SpaceButton("Открыть приложение", filled = false) {
                            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            onClose()
                        }
                    }
                }
            }
        }
    }
}
