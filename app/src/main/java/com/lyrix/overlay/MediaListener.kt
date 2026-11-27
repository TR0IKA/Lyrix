package com.lyrix.overlay

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Слушает активные медиа-сессии. Для этого нужен доступ к уведомлениям.
 * Каждые 2 секунды сам сверяется со списком сессий и переподключается, если система его отвязала.
 */
class MediaListener : NotificationListenerService() {

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        @Volatile
        var instance: MediaListener? = null

        fun rebind(ctx: Context) {
            try {
                NotificationListenerService.requestRebind(ComponentName(ctx, MediaListener::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshNow()
            handler.postDelayed(this, 2000)
        }
    }

    private var msm: MediaSessionManager? = null
    private var comp: ComponentName? = null
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var lastMetaKey: String? = null
    private var lastTrack: Track? = null

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onSessionDestroyed() {
            refreshNow()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            setControllers(list ?: emptyList())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _connected.value = true
        comp = ComponentName(this, MediaListener::class.java)
        msm = getSystemService(MediaSessionManager::class.java)
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, comp)
        } catch (e: SecurityException) {
        }
        refreshNow()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 2000)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        handler.removeCallbacks(poll)
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        controllers.forEach { it.unregisterCallback(cb) }
        controllers = emptyList()
        current = null
        instance = null
        _connected.value = false
        NowPlaying.controller = null
        NowPlaying.set(PlayState())
        rebind(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun refreshNow() {
        try {
            val m = msm ?: return
            val list = m.getActiveSessions(comp)
            val same = list.size == controllers.size &&
                list.indices.all { list[it].sessionToken == controllers[it].sessionToken }
            if (same) publish() else setControllers(list)
        } catch (e: SecurityException) {
        }
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(cb) }
        controllers = list
        controllers.forEach { it.registerCallback(cb) }
        publish()
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Сессия «живая»: играет, буферизуется или подключается — таким отдаём приоритет. */
    private fun isActive(c: MediaController): Boolean {
        val s = c.playbackState?.state
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING ||
            s == PlaybackState.STATE_CONNECTING
    }

    /** Для диагностики (меню разработчика). */
    fun describe(): String {
        if (controllers.isEmpty()) return "нет активных медиа-сессий"
        return controllers.joinToString("\n") { c ->
            val m = c.metadata
            "${c.packageName}: state=${c.playbackState?.state}, " +
                "title=${m?.getString(MediaMetadata.METADATA_KEY_TITLE)}, " +
                "display=${m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)}"
        }
    }

    private fun publish() {
        val cur = current?.let { c -> controllers.firstOrNull { it.sessionToken == c.sessionToken } }
        val best: MediaController? = if (cur != null && isActive(cur)) {
            cur
        } else {
            controllers.firstOrNull { isActive(it) } ?: cur ?: controllers.firstOrNull { it.metadata != null }
        }
        current = best
        NowPlaying.controller = best
        val md = best?.metadata
        if (best == null || md == null) {
            NowPlaying.set(PlayState())
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) {
            NowPlaying.set(PlayState())
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_AUTHOR)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val artUri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val rawArt = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val metaKey = "${best.packageName}|$title|$artist|$dur"
        val prev = lastTrack
        val track = if (prev != null && lastMetaKey == metaKey && (prev.art != null || rawArt == null)) {
            prev
        } else {
            Track(title, artist, album, dur, rawArt?.let { scale(it) }, best.packageName, artUri).also {
                lastTrack = it
                lastMetaKey = metaKey
            }
        }

        val finalTrack = if (track.art == null) {
            NowPlaying.artFor(track.key)?.let { track.copy(art = it) } ?: track
        } else track

        val ps = best.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val pos = ps?.position?.takeIf { it >= 0 } ?: 0L
        val upd = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        NowPlaying.set(PlayState(finalTrack, playing, pos, upd, speed))
    }

    private fun scale(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= 600) return b
        val f = 600f / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }
}
