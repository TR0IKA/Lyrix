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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        const val ACTION_STOP = "com.lyrix.overlay.STOP"
        const val ACTION_EDIT = "com.lyrix.overlay.EDIT"
        private const val CH = "lyrix_overlay"

        fun start(ctx: Context, edit: Boolean = false) {
            val i = Intent(ctx, OverlayService::class.java)
            if (edit) i.action = ACTION_EDIT
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var lyricsView: LyricsView? = null
    private var lyricsLp: WindowManager.LayoutParams? = null
    private var bubble: BubbleView? = null
    private var editing = false

    private var rawLines: List<LrcLine> = emptyList()
    private var lines: List<LrcLine> = emptyList()
    private var lastSplit = -1
    private var lastIdx = -3
    private var coverColor = Color.WHITE
    private var blurBmp: Bitmap? = null
    private var tf: Typeface? = null
    private var tfPath: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (lyricsView == null) setup()
        if (lyricsView == null) return START_NOT_STICKY
        if (intent?.action == ACTION_EDIT) enterEdit()
        _running.value = true
        return START_STICKY
    }

    // ---------------------------------------------------------------- уведомление
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Lyrix", NotificationManager.IMPORTANCE_LOW))
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), fl)
        val menu = PendingIntent.getActivity(
            this, 3,
            Intent(this, MenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl
        )
        val edit = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_EDIT), fl
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), fl
        )
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Lyrix работает")
            .setContentText("Текст песни поверх экрана")
            .setContentIntent(open)
            .addAction(0, "Меню", menu)
            .addAction(0, "Рамки", edit)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- окна и подписки
    private fun setup() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lv = LyricsView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(lv, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "Нет разрешения «поверх других приложений»", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        lyricsView = lv
        lyricsLp = lp

        if (!MediaListener.connected.value) MediaListener.rebind(applicationContext)

        scope.launch { Prefs.config.collect { applyConfig(it) } }

        scope.launch {
            NowPlaying.state.map { it.track?.key }.distinctUntilChanged().collect {
                LyricsManager.load(NowPlaying.state.value.track)
            }
        }
        // цвет и размытие обложки
        scope.launch {
            NowPlaying.state.map { it.track?.art }.distinctUntilChanged().collect { art ->
                if (art != null) {
                    val (col, blur) = withContext(Dispatchers.Default) {
                        Pair(CoverColor.pick(art), Blur.blurred(art))
                    }
                    coverColor = col
                    blurBmp = blur
                } else {
                    coverColor = Color.WHITE
                    blurBmp = null
                }
                updateColors()
            }
        }
        // нет обложки у плеера: берём из избранного, иначе (если не офлайн) ищем в интернете
        scope.launch {
            NowPlaying.state.map { s -> s.track?.takeIf { it.art == null }?.key }.distinctUntilChanged().collect { key ->
                val t = NowPlaying.state.value.track
                if (key == null || t == null || t.key != key) return@collect
                val fav = withContext(Dispatchers.IO) { Favorites.readCover(key) }
                if (fav != null) {
                    NowPlaying.setArt(key, fav)
                    return@collect
                }
                val byUri = t.artUri?.let {
                    ArtFetcher.fromUri(applicationContext, it, !Prefs.config.value.offlineMode)
                }
                if (byUri != null) {
                    NowPlaying.setArt(key, byUri)
                    return@collect
                }
                if (!Prefs.config.value.offlineMode) {
                    val b = ArtFetcher.fetch(t)
                    if (b != null && NowPlaying.state.value.track?.key == key) NowPlaying.setArt(key, b)
                }
            }
        }
        scope.launch { NowPlaying.state.collect { bindBubble() } }
        scope.launch {
            LyricsManager.state.collect { s ->
                rawLines = (s as? LyricsState.Found)?.lines ?: emptyList()
                applyLines()
            }
        }
        scope.launch {
            var noTrackMs = 0L
            while (isActive) {
                tick()
                val step = if (NowPlaying.state.value.playing) 33L else 250L
                delay(step)
                // страховка: если ничего не определяется, пробуем переподключить слушатель
                if (NowPlaying.state.value.track == null) {
                    noTrackMs += step
                    if (noTrackMs >= 6000) {
                        noTrackMs = 0
                        MediaListener.rebind(applicationContext)
                        MediaListener.instance?.refreshNow()
                    }
                } else noTrackMs = 0
            }
        }
    }

    private fun applyLines() {
        val n = Prefs.config.value.splitWords
        lastSplit = n
        lines = Lrc.split(rawLines, n)
        lastIdx = -3
        lyricsView?.setLyrics(lines)
    }

    private fun tick() {
        val lv = lyricsView ?: return
        val s = NowPlaying.state.value
        val cfg = Prefs.config.value
        if (lines.isNotEmpty() && s.track != null) {
            val pos = s.position() + cfg.offsetMs
            val idx = indexFor(pos)
            if (idx != lastIdx) {
                lastIdx = idx
                lv.setIndex(idx)
            }
            lv.setPosition(pos)
        }
    }

    private fun indexFor(pos: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= pos) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }

    // ---------------------------------------------------------------- настройки и цвета
    private fun applyConfig(c: Config) {
        if (c.fontPath != tfPath) {
            tfPath = c.fontPath
            tf = c.fontPath?.let { p -> runCatching { Typeface.createFromFile(p) }.getOrNull() }
        }
        if (c.splitWords != lastSplit) applyLines()
        lyricsView?.applyConfig(c, tf)
        updateColors()
        if (c.bubbleShow && bubble == null && !editing) addBubble(c)
        else if (!c.bubbleShow && bubble != null) removeBubble()
    }

    private fun resolve(mode: Int, custom: Int) = when (mode) {
        1 -> coverColor
        2 -> CoverColor.contrastOf(coverColor)
        else -> custom
    }

    private fun accent(): Int {
        val c = Prefs.config.value
        return resolve(c.colorMode, c.textColor)
    }

    private fun updateColors() {
        val c = Prefs.config.value
        lyricsView?.setColors(resolve(c.colorMode, c.textColor), resolve(c.hiMode, c.hiColor))
        val bd = if (c.bdMode == 1) coverColor else c.bdColor
        lyricsView?.setBackdrop(bd, if (c.bdMode == 2) blurBmp else null)
        bindBubble()
    }

    // ---------------------------------------------------------------- кнопка
    private fun bindBubble() {
        val s = NowPlaying.state.value
        bubble?.bind(Prefs.config.value, s.track, s.playing, accent())
    }

    private fun addBubble(c: Config) {
        val d = resources.displayMetrics.density
        val b = BubbleView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = if (c.bubbleX >= 0) c.bubbleX else (8 * d).toInt()
        lp.y = if (c.bubbleY >= 0) c.bubbleY else (140 * d).toInt()
        b.wm = wm
        b.lp = lp
        b.onMoved = { x, y -> Prefs.update { it.copy(bubbleX = x, bubbleY = y) } }
        b.onClick = { if (Prefs.config.value.bubbleOpensMenu) toggleMenu() }
        try {
            wm.addView(b, lp)
        } catch (e: Exception) {
            return
        }
        bubble = b
        b.animateIn()
        bindBubble()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun toggleMenu() {
        val m = MenuActivity.instance
        if (m != null) {
            m.close()
        } else {
            startActivity(
                Intent(this, MenuActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    // ---------------------------------------------------------------- редактор рамок
    private fun enterEdit() {
        val lv = lyricsView ?: return
        val lp = lyricsLp ?: return
        editing = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(lv, lp) }
        bubble?.visibility = View.GONE
        lv.enterEdit(Prefs.config.value.frames) { frames ->
            Prefs.update { it.copy(frames = frames) }
            exitEdit()
        }
    }

    private fun exitEdit() {
        val lv = lyricsView ?: return
        val lp = lyricsLp ?: return
        editing = false
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(lv, lp) }
        val c = Prefs.config.value
        if (c.bubbleShow) {
            if (bubble == null) addBubble(c) else bubble?.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        if (lyricsView != null) {
            lyricsView?.let { runCatching { wm.removeView(it) } }
            removeBubble()
        }
        lyricsView = null
        LyricsManager.reset()
        super.onDestroy()
    }
}
