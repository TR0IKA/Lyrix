package com.lyrix.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun buildReport(ctx: android.content.Context, perms: PermState): String {
    val dm = ctx.resources.displayMetrics
    val s = NowPlaying.state.value
    val t = s.track
    val sb = StringBuilder()
    sb.appendLine("Lyrix ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
    sb.appendLine("Собрано: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(BuildConfig.BUILD_TIME)))
    sb.appendLine("Пакет: ${BuildConfig.APPLICATION_ID}")
    sb.appendLine()
    sb.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
    sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    sb.appendLine("Экран: ${dm.widthPixels}x${dm.heightPixels}, density ${dm.density}")
    sb.appendLine()
    sb.appendLine("Разрешение «поверх окон»: ${perms.overlay}")
    sb.appendLine("Доступ к уведомлениям: ${perms.listener}")
    sb.appendLine("Слушатель подключён: ${MediaListener.connected.value}")
    sb.appendLine("Оверлей запущен: ${OverlayService.running.value}")
    sb.appendLine()
    sb.appendLine("Трек: ${t?.title ?: "—"} / ${t?.artist ?: "—"}")
    sb.appendLine("Плеер: ${t?.pkg ?: "—"}")
    sb.appendLine("Длительность: ${(t?.durationMs ?: 0) / 1000} с, играет: ${s.playing}, позиция: ${s.position() / 1000} с")
    sb.appendLine("Обложка: ${if (t?.art != null) "${t.art.width}x${t.art.height}" else "нет"}")
    val ls = LyricsManager.state.value
    sb.appendLine("Текст: " + when (ls) {
        is LyricsState.Found -> "найден, ${ls.lines.size} строк"
        LyricsState.Loading -> "загрузка"
        LyricsState.NotFound -> "не найден"
        LyricsState.Idle -> "—"
    })
    sb.appendLine("Кэш текстов: ${LyricsManager.cacheInfo()}")
    sb.appendLine("Избранное: ${Favorites.list.value.size}, файлов в «Моей музыке»: ${Library.list.value.size}")
    sb.appendLine()
    sb.appendLine("Медиа-сессии:")
    sb.appendLine(MediaListener.instance?.describe() ?: "слушатель не подключён")
    sb.appendLine()
    sb.appendLine("Настройки:")
    sb.appendLine(Prefs.config.value.toJson(false).toString(2))
    return sb.toString()
}

@Composable
fun DevScreen(perms: PermState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val report = remember(tick) { buildReport(ctx, perms) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GradientTitle("Разработчик", 28) }
            SpaceButton("Назад", filled = false, onClick = onBack)
        }
        GlassCard {
            SectionTitle("Информация о версии")
            Text(report, color = Color(0xFFCFE9FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
        }
        GlassCard {
            SectionTitle("Действия")
            SpaceButton("Скопировать отчёт") {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Lyrix report", report))
                Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            SpaceButton("Переподключить слушатель", filled = false) {
                MediaListener.rebind(ctx)
                MediaListener.instance?.refreshNow()
                Toast.makeText(ctx, "Запрошено переподключение", Toast.LENGTH_SHORT).show()
            }
            SpaceButton("Очистить кэш текстов", filled = false) {
                LyricsManager.clearCache()
                Toast.makeText(ctx, "Кэш очищен", Toast.LENGTH_SHORT).show()
            }
            SpaceButton("Сбросить настройки", filled = false) {
                Prefs.reset()
                Toast.makeText(ctx, "Настройки сброшены", Toast.LENGTH_SHORT).show()
            }
            SpaceButton("Скрыть меню разработчика", filled = false) {
                Prefs.update { it.copy(devUnlocked = false) }
                onBack()
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
