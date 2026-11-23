package com.lyrix.overlay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    fun load(ctx: Context, track: Track?) {
        job?.cancel()
        if (track == null) {
            _state.value = LyricsState.Idle
            return
        }
        _state.value = LyricsState.Loading
        val dir = ctx.cacheDir
        job = scope.launch {
            val f = File(dir, "lrc_${track.key.hashCode()}.lrc")
            var text: String? = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
            if (text == null) {
                text = fetch(track)
                if (text != null) runCatching { f.writeText(text) }
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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Lyrix/1.0 (Android)")
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun fetch(t: Track): String? {
        val dur = (t.durationMs / 1000).toInt()
        var getUrl = "https://lrclib.net/api/get?track_name=${enc(t.title)}&artist_name=${enc(t.artist)}"
        if (t.album.isNotBlank()) getUrl += "&album_name=${enc(t.album)}"
        if (dur > 0) getUrl += "&duration=$dur"
        val exact = httpGet(getUrl)?.let { body ->
            runCatching { JSONObject(body).optString("syncedLyrics") }.getOrNull()
        }
        if (!exact.isNullOrBlank() && exact != "null") return exact

        val clean = t.title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim().ifEmpty { t.title }
        val urls = listOf(
            "https://lrclib.net/api/search?track_name=${enc(clean)}&artist_name=${enc(t.artist)}",
            "https://lrclib.net/api/search?q=${enc(t.artist + " " + clean)}"
        )
        for (url in urls) {
            val body = httpGet(url) ?: continue
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
        }
        return null
    }
}
