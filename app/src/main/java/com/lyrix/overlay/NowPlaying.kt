package com.lyrix.overlay

import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val art: Bitmap?,
    val pkg: String,
    val artUri: String? = null
) {
    /** Ключ не зависит от длительности: она у разных плееров и запусков отличается. */
    val key: String get() = trackKey(artist, title)
}

fun trackKey(artist: String, title: String): String {
    fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
    return norm(artist) + "|" + norm(title)
}

data class PlayState(
    val track: Track? = null,
    val playing: Boolean = false,
    val posMs: Long = 0,
    val posTime: Long = 0,
    val speed: Float = 1f
) {
    fun position(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (playing) posMs + ((now - posTime) * speed).toLong() else posMs
        return p.coerceAtLeast(0L)
    }
}

object NowPlaying {
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    @Volatile
    var controller: MediaController? = null

    @Volatile
    private var artKey: String? = null

    @Volatile
    private var artBmp: Bitmap? = null

    /** Обложка, найденная приложением (из избранного или интернета), если плеер её не дал. */
    fun artFor(key: String): Bitmap? = if (artKey == key) artBmp else null

    fun setArt(key: String, bmp: Bitmap) {
        artKey = key
        artBmp = bmp
        _state.update { s ->
            val t = s.track
            if (t != null && t.key == key && t.art == null) s.copy(track = t.copy(art = bmp)) else s
        }
    }

    fun set(s: PlayState) {
        _state.value = s
    }

    fun playPause() {
        val c = controller ?: return
        if (_state.value.playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun prev() {
        controller?.transportControls?.skipToPrevious()
    }
}
