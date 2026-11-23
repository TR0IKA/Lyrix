package com.lyrix.overlay

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Слушает активные медиа-сессии (Spotify, YouTube Music, Яндекс Музыка и т.д.).
 * Для этого нужен доступ к уведомлениям.
 */
class MediaListener : NotificationListenerService() {

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
            refresh()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> setControllers(list ?: emptyList()) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        comp = ComponentName(this, MediaListener::class.java)
        msm = getSystemService(MediaSessionManager::class.java)
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, comp)
        } catch (e: SecurityException) {
            return
        }
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        controllers.forEach { it.unregisterCallback(cb) }
        controllers = emptyList()
        current = null
        NowPlaying.controller = null
        NowPlaying.set(PlayState())
    }

    private fun refresh() {
        try {
            val m = msm ?: return
            setControllers(m.getActiveSessions(comp))
        } catch (e: SecurityException) {
        }
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(cb) }
        controllers = list.filter { it.packageName != packageName }
        controllers.forEach { it.registerCallback(cb) }
        publish()
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun publish() {
        val cur = current?.let { c -> controllers.firstOrNull { it.sessionToken == c.sessionToken } }
        val best: MediaController? = if (cur != null && isPlaying(cur)) {
            cur
        } else {
            controllers.firstOrNull { isPlaying(it) } ?: cur ?: controllers.firstOrNull { it.metadata != null }
        }
        current = best
        NowPlaying.controller = best
        val md = best?.metadata
        if (best == null || md == null) {
            NowPlaying.set(PlayState())
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title.isNullOrBlank()) {
            NowPlaying.set(PlayState())
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
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
            Track(title, artist, album, dur, rawArt?.let { scale(it) }, best.packageName).also {
                lastTrack = it
                lastMetaKey = metaKey
            }
        }

        val ps = best.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val pos = ps?.position?.takeIf { it >= 0 } ?: 0L
        val upd = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        NowPlaying.set(PlayState(track, playing, pos, upd, speed))
    }

    private fun scale(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= 600) return b
        val f = 600f / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }
}
