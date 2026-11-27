package com.lyrix.overlay

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class Fav(
    val id: String,
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val pkg: String,
    val addedAt: Long
)

/** Избранное: метаданные, обложка и текст лежат в памяти приложения — открываются мгновенно и без интернета. */
object Favorites {
    lateinit var app: Context
    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _list = MutableStateFlow<List<Fav>>(emptyList())
    val list: StateFlow<List<Fav>> = _list.asStateFlow()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        root = File(app.filesDir, "fav").apply { mkdirs() }
        reload()
    }

    fun idOf(key: String): String =
        MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun dir(key: String): File {
        val known = _list.value.firstOrNull { it.key == key }
        return if (known != null) File(root, known.id) else File(root, idOf(key))
    }
    fun dirOf(id: String) = File(root, id)
    fun isFav(key: String) = _list.value.any { it.key == key }
    fun coverFile(id: String) = File(dirOf(id), "cover.jpg")
    fun lyricsFile(id: String) = File(dirOf(id), "lyrics.lrc")

    fun readLyrics(key: String): String? {
        val f = File(dir(key), "lyrics.lrc")
        return if (f.exists()) runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() } else null
    }

    fun readCover(key: String): Bitmap? {
        val f = File(dir(key), "cover.jpg")
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun saveLyrics(key: String, text: String) {
        if (!isFav(key)) return
        runCatching { File(dir(key), "lyrics.lrc").writeText(text) }
    }

    private fun reload() {
        val out = ArrayList<Fav>()
        root.listFiles()?.forEach { d ->
            val m = File(d, "meta.json")
            if (m.exists()) runCatching {
                val o = JSONObject(m.readText())
                out += Fav(
                    d.name, trackKey(o.optString("artist"), o.getString("title")), o.getString("title"), o.optString("artist"),
                    o.optString("album"), o.optLong("durationMs"), o.optString("pkg"), o.optLong("addedAt")
                )
            }
        }
        _list.value = out.sortedByDescending { it.addedAt }
    }

    fun toggle(t: Track) {
        if (isFav(t.key)) remove(t.key) else add(t)
    }

    /** Добавляет трек из «Моей музыки» в избранное (обложка и текст сохраняются как обычно). */
    fun addFromLib(t: LibTrack) {
        add(Track(t.title, t.artist, t.album, t.durationMs, Library.cover(t), app.packageName))
    }

    fun add(t: Track) {
        val d = dir(t.key)
        d.mkdirs()
        val meta = JSONObject()
            .put("key", t.key).put("title", t.title).put("artist", t.artist).put("album", t.album)
            .put("durationMs", t.durationMs).put("pkg", t.pkg).put("addedAt", System.currentTimeMillis())
        File(d, "meta.json").writeText(meta.toString())
        reload()
        scope.launch {
            val art = t.art ?: NowPlaying.artFor(t.key)
            if (art != null) runCatching {
                File(d, "cover.jpg").outputStream().use { art.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            var text = LyricsManager.localText(t)
            if (text == null && !Prefs.config.value.offlineMode) text = LyricsManager.fetchOnline(t)
            if (text != null) runCatching { File(d, "lyrics.lrc").writeText(text) }
            reload()
        }
    }

    fun remove(key: String) {
        dir(key).deleteRecursively()
        reload()
    }

    fun removeById(id: String) {
        dirOf(id).deleteRecursively()
        reload()
    }
}

/** Свои тексты (.lrc или обычный текст), привязанные к треку. Имеют приоритет над всем остальным. */
object LocalStore {
    private fun f(key: String): File {
        val d = File(Favorites.app.filesDir, "custom").apply { mkdirs() }
        return File(d, Favorites.idOf(key) + ".lrc")
    }

    fun custom(key: String): String? =
        f(key).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }

    fun hasCustom(key: String) = f(key).exists()
    fun clear(key: String) {
        f(key).delete()
    }

    fun importText(key: String, raw: String, durMs: Long): Boolean {
        val text = normalize(raw, durMs) ?: return false
        f(key).writeText(text)
        return true
    }

    /** Если в тексте нет таймкодов — равномерно растягиваем строки по длине трека (потом подвинь «Синхронизацией»). */
    private fun normalize(raw: String, durMs: Long): String? {
        if (Lrc.parse(raw).isNotEmpty()) return raw
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val total = if (durMs > 0) durMs else 200_000L
        val step = total / (lines.size + 1)
        return lines.mapIndexed { i, s ->
            val t = step / 2 + step * i
            String.format(Locale.US, "[%02d:%02d.%02d]%s", t / 60000, (t / 1000) % 60, (t % 1000) / 10, s)
        }.joinToString("\n")
    }
}

/** Запуск избранного трека в том плеере, откуда он был добавлен. */
object FavLauncher {
    fun play(ctx: Context, f: Fav) {
        val local = Library.find(f.key)
        if (local != null) {
            PlayerService.play(ctx, local.id)
            return
        }
        val q = "${f.artist} ${f.title}".trim()
        val c = NowPlaying.controller
        if (c != null && c.packageName == f.pkg) {
            try {
                c.transportControls.playFromSearch(q, Bundle())
                return
            } catch (e: Exception) {
            }
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            putExtra(MediaStore.EXTRA_MEDIA_TITLE, f.title)
            putExtra(MediaStore.EXTRA_MEDIA_ARTIST, f.artist)
            putExtra(SearchManager.QUERY, q)
            if (f.pkg.isNotEmpty()) setPackage(f.pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            val l = if (f.pkg.isNotEmpty()) ctx.packageManager.getLaunchIntentForPackage(f.pkg) else null
            if (l != null) {
                l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(l)
                Toast.makeText(ctx, "Открыл плеер — найди трек: $q", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(ctx, "Не удалось открыть плеер", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
