package com.lyrix.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LrcLine(val timeMs: Long, val text: String)

object Lrc {
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    fun parse(src: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (raw in src.lines()) {
            val ms = tag.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val text = raw.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val milli = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                out += LrcLine((min * 60 + sec) * 1000 + milli, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Делит каждую строку на части по n слов; время частей распределяется по длине строки. */
    fun split(lines: List<LrcLine>, n: Int): List<LrcLine> {
        if (n <= 0) return lines
        val out = ArrayList<LrcLine>()
        for (i in lines.indices) {
            val l = lines[i]
            val words = l.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size <= n) {
                out += l
                continue
            }
            val t0 = l.timeMs
            val t1 = lines.getOrNull(i + 1)?.timeMs ?: (t0 + 5000)
            val total = words.sumOf { it.length + 1 }
            val dur = minOf(t1 - t0, maxOf(1500L, total * 150L)).coerceAtLeast(300L)
            var acc = 0
            var w = 0
            while (w < words.size) {
                val chunk = words.subList(w, minOf(w + n, words.size))
                out += LrcLine(t0 + dur * acc / total, chunk.joinToString(" "))
                acc += chunk.sumOf { it.length + 1 }
                w += n
            }
        }
        return out
    }
}

object Net {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Lyrix/1.1 (Android)")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }
}

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object NotFound : LyricsState
    class Found(val lines: List<LrcLine>) : LyricsState
}

object LyricsManager {
    private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private fun cacheFile(t: Track) = File(Favorites.app.cacheDir, "lrc_${t.key.hashCode()}.lrc")

    /** Только то, что уже лежит на устройстве: свой текст → избранное → кэш. */
    fun localText(t: Track): String? {
        LocalStore.custom(t.key)?.let { return it }
        Favorites.readLyrics(t.key)?.let { return it }
        val f = cacheFile(t)
        return if (f.exists()) runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() } else null
    }

    fun reload() = load(NowPlaying.state.value.track)

    fun load(track: Track?) {
        job?.cancel()
        if (track == null) {
            _state.value = LyricsState.Idle
            return
        }
        _state.value = LyricsState.Loading
        job = scope.launch {
            var text = localText(track)
            if (text == null && !Prefs.config.value.offlineMode) {
                text = fetchOnline(track)
                if (text != null) {
                    runCatching { cacheFile(track).writeText(text) }
                    if (Favorites.isFav(track.key)) Favorites.saveLyrics(track.key, text)
                }
            }
            ensureActive()
            val lines = text?.let { Lrc.parse(it) }.orEmpty()
            _state.value = if (lines.isEmpty()) LyricsState.NotFound else LyricsState.Found(lines)
        }
    }

    fun reset() {
        job?.cancel()
        _state.value = LyricsState.Idle
    }

    fun clearCache() {
        Favorites.app.cacheDir.listFiles()?.filter { it.name.startsWith("lrc_") }?.forEach { it.delete() }
    }

    fun cacheInfo(): String {
        val files = Favorites.app.cacheDir.listFiles()?.filter { it.name.startsWith("lrc_") } ?: emptyList()
        return "${files.size} файл(ов), ${files.sumOf { it.length() } / 1024} КБ"
    }

    /** Ищет синхронизированный текст на lrclib.net (учитывает ремиксы: убирает «- Remix», скобки и т.п.). */
    fun fetchOnline(t: Track): String? {
        val dur = (t.durationMs / 1000).toInt()
        var getUrl = "https://lrclib.net/api/get?track_name=${Net.enc(t.title)}&artist_name=${Net.enc(t.artist)}"
        if (t.album.isNotBlank()) getUrl += "&album_name=${Net.enc(t.album)}"
        if (dur > 0) getUrl += "&duration=$dur"
        val exact = Net.get(getUrl)?.let { body ->
            runCatching { JSONObject(body).optString("syncedLyrics") }.getOrNull()
        }
        if (!exact.isNullOrBlank() && exact != "null") return exact

        var clean = t.title.replace(Regex("""\s*[(\[].*?[)\]]"""), "")
        clean = clean.replace(
            Regex("""\s+[-–]\s+.*(remix|mix|edit|version|remaster|live|slowed|sped|reverb|acoustic).*""", RegexOption.IGNORE_CASE),
            ""
        ).trim().ifEmpty { t.title }
        val urls = listOf(
            "https://lrclib.net/api/search?track_name=${Net.enc(clean)}&artist_name=${Net.enc(t.artist)}",
            "https://lrclib.net/api/search?q=${Net.enc(t.artist + " " + clean)}",
            "https://lrclib.net/api/search?q=${Net.enc(clean)}"
        )
        var loose: String? = null
        var looseDiff = Int.MAX_VALUE
        for (url in urls) {
            val body = Net.get(url) ?: continue
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: continue
            var best: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = o.optString("syncedLyrics")
                if (s.isBlank() || s == "null") continue
                val diff = if (dur > 0) abs(o.optInt("duration") - dur) else 0
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = s
                }
            }
            if (best != null && (dur <= 0 || bestDiff <= 15)) return best
            if (best != null && bestDiff < looseDiff) {
                loose = best
                looseDiff = bestDiff
            }
        }
        // ремикс с другой длиной: берём ближайший по длине, тайминг можно подвинуть ползунком «Синхронизация»
        if (loose != null && looseDiff <= 90) return loose
        // запасной источник
        return fetchNetease(t, clean)
    }

    private val neteaseHeaders = mapOf("Referer" to "https://music.163.com/", "Cookie" to "appver=2.0.2; os=pc")

    /** Запасной поиск синхронизированного текста (NetEase) — если на lrclib трека нет. */
    private fun fetchNetease(t: Track, clean: String): String? {
        return try {
            val q = Net.enc(t.artist + " " + clean)
            val body = Net.get(
                "https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=$q&type=1&offset=0&total=true&limit=6",
                neteaseHeaders
            ) ?: return null
            val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
            val dur = (t.durationMs / 1000).toInt()
            val cl = clean.lowercase()
            var bestId = -1L
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until songs.length()) {
                val o = songs.optJSONObject(i) ?: continue
                val name = o.optString("name").lowercase()
                if (!(name.contains(cl) || cl.contains(name))) continue
                val diff = if (dur > 0) abs(o.optInt("duration") / 1000 - dur) else 0
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestId = o.optLong("id")
                }
            }
            if (bestId < 0 || (dur > 0 && bestDiff > 20)) return null
            val lb = Net.get("https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=1&tv=-1", neteaseHeaders)
                ?: return null
            val lrc = JSONObject(lb).optJSONObject("lrc")?.optString("lyric")
            if (!lrc.isNullOrBlank() && Lrc.parse(lrc).isNotEmpty()) lrc else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Ищет обложку в интернете, если плеер её не передал (iTunes Search). */
object ArtFetcher {
    private fun shrink(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= 700) return b
        val f = 700f / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    /** Многие плееры отдают обложку не картинкой, а ссылкой (content:// или https://). */
    suspend fun fromUri(ctx: Context, u: String, allowNet: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bmp = if (u.startsWith("http", ignoreCase = true)) {
                if (allowNet) URL(u).openStream().use { BitmapFactory.decodeStream(it) } else null
            } else {
                ctx.contentResolver.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) }
            }
            bmp?.let { shrink(it) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetch(t: Track): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val q = Net.enc("${t.artist} ${t.title}")
            val body = Net.get("https://itunes.apple.com/search?term=$q&entity=song&limit=1")
            val url = body?.let { JSONObject(it).getJSONArray("results").optJSONObject(0)?.optString("artworkUrl100") }
            if (url.isNullOrBlank()) null
            else URL(url.replace("100x100", "600x600")).openStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
}

object Blur {
    /** Дешёвое размытие: уменьшаем до 48 px и размываем боксом. */
    fun blurred(src: Bitmap): Bitmap {
        val size = 48
        val small = Bitmap.createScaledBitmap(src, size, size, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(size * size)
        small.getPixels(px, 0, size, 0, 0, size, size)
        repeat(2) {
            box(px, size, true)
            box(px, size, false)
        }
        small.setPixels(px, 0, size, 0, 0, size, size)
        return small
    }

    private fun box(px: IntArray, n: Int, horizontal: Boolean) {
        val r = 4
        val tmp = IntArray(px.size)
        for (y in 0 until n) for (x in 0 until n) {
            var a = 0; var rr = 0; var g = 0; var b = 0; var cnt = 0
            for (k in -r..r) {
                val xx = if (horizontal) x + k else x
                val yy = if (horizontal) y else y + k
                if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue
                val c = px[yy * n + xx]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c); cnt++
            }
            tmp[y * n + x] = Color.argb(a / cnt, rr / cnt, g / cnt, b / cnt)
        }
        System.arraycopy(tmp, 0, px, 0, px.size)
    }
}
