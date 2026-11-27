package com.lyrix.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Встроенный плеер для файлов из «Моей музыки». Публикует обычную медиа-сессию,
 * поэтому оверлей с текстом работает с ним так же, как с любым другим плеером.
 */
class PlayerService : Service() {

    companion object {
        private val _current = MutableStateFlow<LibTrack?>(null)
        val current: StateFlow<LibTrack?> = _current.asStateFlow()

        const val A_TOGGLE = "com.lyrix.overlay.player.TOGGLE"
        const val A_NEXT = "com.lyrix.overlay.player.NEXT"
        const val A_PREV = "com.lyrix.overlay.player.PREV"
        const val A_STOP = "com.lyrix.overlay.player.STOP"
        private const val CH = "lyrix_player"

        fun play(ctx: Context, id: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlayerService::class.java).putExtra("id", id))
        }
    }

    private var mp: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var am: AudioManager
    private var focusReq: AudioFocusRequest? = null
    private var queue: List<LibTrack> = emptyList()
    private var index = 0
    private var cover: Bitmap? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { c ->
        if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (mp?.isPlaying == true) {
                mp?.pause()
                update()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "LyrixPlayer")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSkipToNext() = startTrack(index + 1)
            override fun onSkipToPrevious() = previous()
            override fun onSeekTo(pos: Long) {
                mp?.seekTo(pos.toInt())
                update()
            }

            override fun onStop() {
                stopSelf()
            }
        })
        session.isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> if (mp?.isPlaying == true) pause() else resume()
            A_NEXT -> startTrack(index + 1)
            A_PREV -> previous()
            A_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val id = intent?.getStringExtra("id")
                goForeground()
                if (id == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                queue = Library.list.value
                index = queue.indexOfFirst { it.id == id }.coerceAtLeast(0)
                startTrack(index)
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Lyrix Player", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(2, n)
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        am.requestAudioFocus(req)
        focusReq = req
    }

    private fun startTrack(i: Int) {
        if (queue.isEmpty()) return
        index = ((i % queue.size) + queue.size) % queue.size
        val t = queue[index]
        mp?.release()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            p.setDataSource(Library.file(t).path)
            p.setOnCompletionListener { startTrack(index + 1) }
            p.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Не удалось воспроизвести файл", Toast.LENGTH_SHORT).show()
                true
            }
            p.prepare()
            requestFocus()
            p.start()
        } catch (e: Exception) {
            p.release()
            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
            return
        }
        mp = p
        _current.value = t
        cover = Library.cover(t)
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, p.duration.toLong())
        cover?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(md.build())
        update()
    }

    private fun resume() {
        val p = mp ?: return
        requestFocus()
        p.start()
        update()
    }

    private fun pause() {
        mp?.pause()
        update()
    }

    private fun previous() {
        val p = mp
        if (p != null && p.currentPosition > 3000) {
            p.seekTo(0)
            update()
        } else startTrack(index - 1)
    }

    private fun update() {
        val p = mp ?: return
        val playing = p.isPlaying
        val pos = try {
            p.currentPosition.toLong()
        } catch (e: Exception) {
            0L
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    pos, 1f, SystemClock.elapsedRealtime()
                )
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(2, buildNotif())
    }

    private fun buildNotif(): Notification {
        val t = _current.value
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun act(code: Int, a: String) =
            PendingIntent.getService(this, code, Intent(this, PlayerService::class.java).setAction(a), fl)

        val playing = mp?.isPlaying == true
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(t?.title ?: "Lyrix Player")
            .setContentText(t?.artist ?: "")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), fl))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Назад", act(11, A_PREV)).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    if (playing) "Пауза" else "Играть", act(12, A_TOGGLE)
                ).build()
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Вперёд", act(13, A_NEXT)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Стоп", act(14, A_STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        cover?.let { b.setLargeIcon(it) }
        return b.build()
    }

    override fun onDestroy() {
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        session.isActive = false
        session.release()
        _current.value = null
        super.onDestroy()
    }
}
