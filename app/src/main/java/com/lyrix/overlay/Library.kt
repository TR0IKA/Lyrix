package com.lyrix.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibTrack(
    val id: String,
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val ext: String
)

/** «Моя музыка»: аудиофайлы пользователя, скопированные в память приложения. Играют во встроенном плеере. */
object Library {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _list = MutableStateFlow<List<LibTrack>>(emptyList())
    val list: StateFlow<List<LibTrack>> = _list.asStateFlow()

    private fun root(): File = File(Favorites.app.filesDir, "music").apply { mkdirs() }
    private fun indexFile() = File(root(), "index.json")

    fun file(t: LibTrack) = File(root(), "${t.id}.${t.ext}")
    fun coverFile(t: LibTrack) = File(root(), "${t.id}.jpg")
    fun find(key: String): LibTrack? = _list.value.firstOrNull { it.key == key }
    fun cover(t: LibTrack): Bitmap? = coverFile(t).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun init() {
        try {
            val f = indexFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val out = ArrayList<LibTrack>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = LibTrack(
                    o.getString("id"), o.getString("key"), o.getString("title"), o.optString("artist"),
                    o.optString("album"), o.optLong("durationMs"), o.optString("ext", "mp3")
                )
                if (file(t).exists()) out += t
            }
            _list.value = out.sortedBy { it.title.lowercase() }
        } catch (e: Exception) {
        }
    }

    private fun save() {
        val arr = JSONArray()
        _list.value.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("key", it.key).put("title", it.title).put("artist", it.artist)
                    .put("album", it.album).put("durationMs", it.durationMs).put("ext", it.ext)
            )
        }
        runCatching { indexFile().writeText(arr.toString()) }
    }

    fun remove(id: String) {
        _list.value.firstOrNull { it.id == id }?.let {
            file(it).delete()
            coverFile(it).delete()
        }
        _list.value = _list.value.filter { it.id != id }
        save()
    }

    /** Копирует выбранные файлы в приложение. Если передан attach — файл привязывается к этому избранному треку. */
    fun importAsync(ctx: Context, uris: List<Uri>, attach: Fav?, onDone: (Int) -> Unit) {
        scope.launch {
            var n = 0
            for (u in uris) if (importOne(ctx, u, attach)) n++
            withContext(Dispatchers.Main) { onDone(n) }
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "track.mp3"
        } catch (e: Exception) {
            "track.mp3"
        }
    }

    private fun importOne(ctx: Context, uri: Uri, attach: Fav?): Boolean {
        try {
            val name = displayName(ctx, uri)
            val ext = name.substringAfterLast('.', "mp3").lowercase().takeIf { it.length in 2..5 } ?: "mp3"
            val tmp = File(root(), "tmp_${System.nanoTime()}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                ?: return false

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var dur = 0L
            var pic: ByteArray? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(tmp.path)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                pic = r.embeddedPicture
            } catch (e: Exception) {
            } finally {
                r.release()
            }

            val t = attach?.title ?: title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
            val a = attach?.artist ?: artist.orEmpty()
            val al = attach?.album ?: album.orEmpty()
            val key = trackKey(a, t)
            val old = find(key)
            val id = old?.id ?: Favorites.idOf(key)
            old?.let { file(it).delete() }
            val entry = LibTrack(id, key, t, a, al, if (dur > 0) dur else (attach?.durationMs ?: 0L), ext)
            if (!tmp.renameTo(file(entry))) {
                tmp.copyTo(file(entry), overwrite = true)
                tmp.delete()
            }
            val picBytes = pic
            if (picBytes != null) {
                runCatching { coverFile(entry).writeBytes(picBytes) }
            } else if (attach != null) {
                val fc = Favorites.coverFile(attach.id)
                if (fc.exists()) runCatching { fc.copyTo(coverFile(entry), overwrite = true) }
            }
            _list.value = (_list.value.filter { it.id != id } + entry).sortedBy { it.title.lowercase() }
            save()
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
