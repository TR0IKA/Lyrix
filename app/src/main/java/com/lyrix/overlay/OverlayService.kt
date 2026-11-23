package com.lyrix.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var card: PlayerCard? = null
    private var editing = false

    private var lines: List<LrcLine> = emptyList()
    private var lastIdx = -3
    private var coverColor = Color.WHITE
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

    // ---------- foreground notification ----------
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Lyrix", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val edit = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_EDIT), flags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Lyrix работает")
            .setContentText("Текст песни поверх экрана")
            .setContentIntent(open)
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

    // ---------- окна ----------
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
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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

        scope.launch { Prefs.config.collect { applyConfig(it) } }
        scope.launch {
            NowPlaying.state.map { it.track?.key }.distinctUntilChanged().collect {
                LyricsManager.load(applicationContext, NowPlaying.state.value.track)
            }
        }
        scope.launch {
            NowPlaying.state.map { it.track?.art }.distinctUntilChanged().collect { art ->
                coverColor = if (art != null) {
                    withContext(Dispatchers.Default) { CoverColor.pick(art) }
                } else Color.WHITE
                updateColors()
            }
        }
        scope.launch { NowPlaying.state.collect { bindCard() } }
        scope.launch {
            LyricsManager.state.collect { s ->
                lines = (s as? LyricsState.Found)?.lines ?: emptyList()
                lastIdx = -3
            }
        }
        scope.launch {
            while (isActive) {
                tick()
                delay(if (NowPlaying.state.value.playing) 50L else 250L)
            }
        }
    }

    private fun tick() {
        val lv = lyricsView ?: return
        val s = NowPlaying.state.value
        val cfg = Prefs.config.value
        if (lines.isNotEmpty() && s.track != null) {
            val idx = indexFor(s.position() + cfg.offsetMs)
            if (idx != lastIdx) {
                lastIdx = idx
                val curText = if (idx >= 0) lines[idx].text else ""
                val nextText = if (curText.isBlank() || !cfg.showNext) "" else lines.getOrNull(idx + 1)?.text.orEmpty()
                lv.setLines(curText, nextText)
            }
        } else if (lastIdx != -2) {
            lastIdx = -2
            lv.setLines("", "")
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

    private fun applyConfig(c: Config) {
        if (c.fontPath != tfPath) {
            tfPath = c.fontPath
            tf = c.fontPath?.let { p -> runCatching { Typeface.createFromFile(p) }.getOrNull() }
        }
        lyricsView?.applyConfig(c, tf)
        updateColors()
        lastIdx = -3
        if (c.playerCard && card == null && !editing) addCard(c)
        else if (!c.playerCard && card != null) removeCard()
    }

    private fun accent(): Int {
        val c = Prefs.config.value
        return if (c.colorFromCover) coverColor else c.textColor
    }

    private fun updateColors() {
        lyricsView?.setTextColor(accent())
        bindCard()
    }

    private fun bindCard() {
        val s = NowPlaying.state.value
        card?.bind(s.track, s.playing, accent())
    }

    private fun addCard(c: Config) {
        val d = resources.displayMetrics.density
        val cd = PlayerCard(this)
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
        lp.x = if (c.playerX >= 0) c.playerX else (16 * d).toInt()
        lp.y = if (c.playerY >= 0) c.playerY else (110 * d).toInt()
        cd.wm = wm
        cd.lp = lp
        cd.onMoved = { x, y -> Prefs.update { it.copy(playerX = x, playerY = y) } }
        try {
            wm.addView(cd, lp)
        } catch (e: Exception) {
            return
        }
        card = cd
        cd.animateIn()
        bindCard()
    }

    private fun removeCard() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    // ---------- редактор рамок ----------
    private fun enterEdit() {
        val lv = lyricsView ?: return
        val lp = lyricsLp ?: return
        editing = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(lv, lp) }
        card?.visibility = View.GONE
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
        if (c.playerCard) {
            if (card == null) addCard(c) else card?.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        lyricsView?.let { runCatching { wm.removeView(it) } }
        removeCard()
        lyricsView = null
        LyricsManager.reset()
        super.onDestroy()
    }
}
