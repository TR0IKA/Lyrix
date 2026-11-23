package com.lyrix.overlay

import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val art: Bitmap?,
    val pkg: String
) {
    val key: String get() = "$artist|$title|$durationMs"
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
