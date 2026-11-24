package com.lyrix.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.SparseArray
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * Полноэкранный прозрачный вид. Стили: 0 классика, 1 караоке, 2 поп-ап, 3 колесо.
 * Не рисует текст в рамках и (опционально) в центральной полосе. В режиме редактирования двигаем рамки.
 */
class LyricsView(ctx: Context) : View(ctx) {

    private class Tf(
        var alpha: Float = 1f, var dx: Float = 0f, var dy: Float = 0f,
        var sx: Float = 1f, var sy: Float = 1f, var rot: Float = 0f,
        var reveal: Float = 1f, var glow: Float = 0f
    )

    private val dp = resources.displayMetrics.density
    private var cfg = Config()
    private var lines: List<LrcLine> = emptyList()
    private var curIdx = -2
    private var mainColor = Color.WHITE
    private var hiColor = Color.YELLOW
    private val rnd = Random(System.nanoTime())

    private val curPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val hiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val nextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val wheelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var cur = ""
    private var next = ""
    private var curLayout: StaticLayout? = null
    private var curHiLayout: StaticLayout? = null
    private var oldLayout: StaticLayout? = null
    private var oldHiLayout: StaticLayout? = null
    private var nextLayout: StaticLayout? = null
    private var progress = 1f
    private var hp = 0f

    // поп-ап
    private var popX = 0f
    private var popY = 0f
    private var popRot = 0f
    private var oldX = 0f
    private var oldY = 0f
    private var oldRot = 0f

    // колесо
    private var wheelPos = -1f
    private val wheelCache = SparseArray<StaticLayout>()
    private val wheelAnim = ValueAnimator().apply {
        addUpdateListener {
            wheelPos = it.animatedValue as Float
            invalidate()
        }
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    // подложка
    private val bdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val bdRect = RectF()
    private val bdTarget = RectF()
    private var bdVis = 0f
    private var bdColor = Color.BLACK
    private var bdBlur: Bitmap? = null
    private var bdShader: BitmapShader? = null
    private val bdMatrix = Matrix()

    // редактор рамок
    private var editMode = false
    private val editFrames = mutableListOf<RectF>()
    private var selected = -1
    private var dragMode = 0
    private var lastX = 0f
    private var lastY = 0f
    private var onDone: ((List<Frame>) -> Unit)? = null
    private val btnRects = Array(3) { RectF() }
    private val btnLabels = arrayOf("+ Рамка", "Удалить", "Готово")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ui = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 14 * dp
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4 * dp, 0f, 1 * dp, 0xCC000000.toInt())
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun withAlpha(c: Int, f: Float) = (c and 0x00FFFFFF) or ((255 * f).toInt().coerceIn(0, 255) shl 24)
    private fun rowH() = curPaint.textSize * 1.75f

    // ------------------------------------------------------------------ публичное API

    fun applyConfig(c: Config, tf: Typeface?) {
        val styleChanged = c.subStyle != cfg.subStyle
        cfg = c
        val t = tf ?: Typeface.DEFAULT_BOLD
        for (p in arrayOf(curPaint, hiPaint, nextPaint, wheelPaint)) {
            p.typeface = t
            p.setShadowLayer(6 * dp, 0f, 2 * dp, 0xB3000000.toInt())
        }
        val sz = sp(c.textSizeSp)
        curPaint.textSize = sz
        hiPaint.textSize = sz
        wheelPaint.textSize = sz
        nextPaint.textSize = sp(c.textSizeSp * 0.62f)
        refreshColors()
        rebuild()
        if (styleChanged) {
            wheelAnim.cancel()
            wheelPos = curIdx.toFloat()
            if (c.subStyle == 2) pickPopup()
        }
        invalidate()
    }

    fun setColors(main: Int, hi: Int) {
        mainColor = main
        hiColor = hi
        refreshColors()
    }

    fun setBackdrop(color: Int, blur: Bitmap?) {
        bdColor = color
        if (blur !== bdBlur) {
            bdBlur = blur
            bdShader = blur?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
        invalidate()
    }

    fun setLyrics(l: List<LrcLine>) {
        lines = l
        curIdx = -2
        wheelCache.clear()
        wheelAnim.cancel()
        wheelPos = -1f
        rebuild()
        invalidate()
    }

    fun setIndex(idx: Int) {
        if (idx == curIdx) return
        curIdx = idx
        oldLayout = curLayout
        oldHiLayout = curHiLayout
        buildCurrent()
        if (cfg.subStyle == 2) pickPopup()
        if (cfg.subStyle == 3) startWheel(idx)
        startAnim()
        hp = computeHp(lastPos)
    }

    private var lastPos = 0L

    fun setPosition(pos: Long) {
        lastPos = pos
        if (cfg.subStyle != 1) return
        val n = computeHp(pos)
        if (n != hp) {
            hp = n
            invalidate()
        }
    }

    // ------------------------------------------------------------------ внутреннее

    private fun refreshColors() {
        curPaint.color = if (cfg.subStyle == 1) withAlpha(mainColor, cfg.kDim) else mainColor
        hiPaint.color = hiColor
        nextPaint.color = withAlpha(mainColor, 0.62f)
        invalidate()
    }

    private fun popFrac() = if (cfg.subStyle == 2) 0.78f else 1f

    private fun layoutOf(text: String, p: TextPaint, wFrac: Float = 1f, maxLines: Int = 0): StaticLayout? {
        if (text.isBlank() || width <= 0) return null
        val w = ((width - 48 * dp) * wFrac).toInt().coerceAtLeast(100)
        val b = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
        if (maxLines > 0) b.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        return b.build()
    }

    private fun buildCurrent() {
        val idx = curIdx
        cur = if (idx in lines.indices) lines[idx].text else ""
        next = if (cur.isBlank() || !cfg.showNext) "" else lines.getOrNull(idx + 1)?.text.orEmpty()
        curLayout = layoutOf(cur, curPaint, popFrac())
        curHiLayout = layoutOf(cur, hiPaint, popFrac())
        nextLayout = layoutOf(next, nextPaint)
    }

    private fun rebuild() {
        buildCurrent()
        oldLayout = null
        oldHiLayout = null
        wheelCache.clear()
    }

    private fun startAnim() {
        animator.cancel()
        animator.interpolator = when (cfg.anim) {
            7 -> OvershootInterpolator(1.8f)
            9 -> LinearInterpolator()
            else -> DecelerateInterpolator(1.6f)
        }
        animator.duration = cfg.animMs.toLong()
        animator.start()
    }

    private fun startWheel(idx: Int) {
        wheelAnim.cancel()
        if (abs(idx - wheelPos) > 4) wheelPos = idx - 1f
        wheelAnim.setFloatValues(wheelPos, idx.toFloat())
        wheelAnim.duration = cfg.animMs.toLong().coerceIn(350L, 900L)
        wheelAnim.interpolator = OvershootInterpolator(0.7f)
        wheelAnim.start()
    }

    private fun computeHp(pos: Long): Float {
        val idx = curIdx
        if (idx !in lines.indices || cur.isBlank()) return 0f
        val t0 = lines[idx].timeMs
        val t1 = lines.getOrNull(idx + 1)?.timeMs ?: (t0 + 5000)
        val len = cur.length
        val dur = minOf(t1 - t0, maxOf(1500L, len * 150L)).coerceAtLeast(300L)
        val frac = ((pos - t0).toFloat() / dur).coerceIn(0f, 1f)
        if (!cfg.kWordSnap) return frac * len
        var i = 0
        var best = 0f
        while (i < len) {
            while (i < len && cur[i] == ' ') i++
            if (i >= len) break
            val s = i
            while (i < len && cur[i] != ' ') i++
            if (s.toFloat() / len <= frac) best = i.toFloat() else break
        }
        return best
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        layoutButtons()
    }

    // ------------------------------------------------------------------ анимации появления

    private fun enterTf(p: Float): Tf {
        val a = p.coerceIn(0f, 1f)
        val t = Tf(alpha = a)
        val tr = 34 * dp
        val x = 90 * dp
        when (cfg.anim) {
            1 -> t.dy = (1f - p) * tr
            2 -> t.dy = -(1f - p) * tr
            3 -> t.dx = -(1f - p) * x
            4 -> t.dx = (1f - p) * x
            5 -> { val s = 0.4f + 0.6f * p; t.sx = s; t.sy = s }
            6 -> { val s = 1.6f - 0.6f * p; t.sx = s; t.sy = s }
            7 -> { val s = 0.5f + 0.5f * p; t.sx = s; t.sy = s }
            8 -> { t.rot = -14f * (1f - p); t.dy = (1f - p) * tr * 0.6f }
            9 -> { t.reveal = a; t.alpha = minOf(1f, a * 8f) }
            10 -> t.glow = 1f - a
            11 -> t.sy = a.coerceAtLeast(0.02f)
        }
        return t
    }

    private fun exitTf(a: Float): Tf {
        val t = Tf(alpha = (1f - a * 1.5f).coerceIn(0f, 1f))
        val tr = 34 * dp
        val x = 90 * dp
        when (cfg.anim) {
            1 -> t.dy = -tr * a
            2 -> t.dy = tr * a
            3 -> t.dx = x * a
            4 -> t.dx = -x * a
            5 -> { val s = 1f + 0.5f * a; t.sx = s; t.sy = s }
            6 -> { val s = 1f - 0.4f * a; t.sx = s; t.sy = s }
            7 -> { val s = 1f - 0.15f * a; t.sx = s; t.sy = s }
            8 -> t.rot = 14f * a
            11 -> t.sy = (1f - a).coerceAtLeast(0.02f)
        }
        return t
    }

    // ------------------------------------------------------------------ рисование

    private fun textW(l: StaticLayout): Float {
        var m = 0f
        for (i in 0 until l.lineCount) m = max(m, l.getLineWidth(i))
        return m
    }

    private fun highlightPath(l: StaticLayout, h: Float): Path {
        val path = Path()
        for (i in 0 until l.lineCount) {
            val s = l.getLineStart(i)
            val e = l.getLineEnd(i)
            if (h <= s) break
            val left = l.getLineLeft(i)
            val right = l.getLineRight(i)
            val x = if (h >= e) right + 4 * dp else {
                val ci = h.toInt().coerceIn(s, maxOf(s, e - 1))
                val frac = h - ci
                val x0 = l.getPrimaryHorizontal(ci)
                val x1 = l.getPrimaryHorizontal(minOf(ci + 1, e))
                x0 + (x1 - x0) * frac
            }
            path.addRect(left - 4 * dp, l.getLineTop(i).toFloat(), x, l.getLineBottom(i).toFloat(), Path.Direction.CW)
        }
        return path
    }

    private fun drawTf(
        c: Canvas, l: StaticLayout, cx: Float, cy: Float, t: Tf,
        hi: StaticLayout?, h: Float, extraRot: Float = 0f
    ) {
        val alpha = t.alpha * cfg.textAlpha
        if (alpha <= 0.01f) return
        val w = l.width.toFloat()
        val hh = l.height.toFloat()
        val margin = 170 * dp
        val count = if (alpha < 0.99f)
            c.saveLayerAlpha(cx - w * 0.9f - margin, cy - hh - margin, cx + w * 0.9f + margin, cy + hh + margin, (alpha * 255).toInt())
        else c.save()
        c.translate(cx + t.dx, cy + t.dy)
        c.rotate(t.rot + extraRot)
        c.scale(t.sx, t.sy)
        c.translate(-w / 2f, -hh / 2f)
        if (t.reveal < 0.999f) c.clipPath(highlightPath(l, t.reveal * l.text.length))
        val gp = l.paint
        if (t.glow > 0.01f) gp.setShadowLayer((8 + 30 * t.glow) * dp, 0f, 0f, withAlpha(gp.color, 0.9f) or 0xE6000000.toInt())
        l.draw(c)
        if (hi != null && h > 0f) {
            c.save()
            c.clipPath(highlightPath(hi, h))
            hi.draw(c)
            c.restore()
        }
        if (t.glow > 0.01f) gp.setShadowLayer(6 * dp, 0f, 2 * dp, 0xB3000000.toInt())
        c.restoreToCount(count)
    }

    override fun onDraw(canvas: Canvas) {
        if (editMode) {
            drawEdit(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        for (f in cfg.frames) canvas.clipOutRect(f.l * w, f.t * h, f.r * w, f.b * h)
        if (cfg.hideCenter) {
            val bh = cfg.centerSize * h
            canvas.clipOutRect(0f, (h - bh) / 2f, w, (h + bh) / 2f)
        }
        drawBackdrop(canvas)
        when (cfg.subStyle) {
            1 -> drawClassic(canvas, true)
            2 -> drawPopup(canvas)
            3 -> drawWheel(canvas)
            else -> drawClassic(canvas, false)
        }
        canvas.restore()
    }

    private fun computeBackdropTarget(): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val padX = 18 * dp
        val padY = 12 * dp
        return when (cfg.subStyle) {
            3 -> {
                if (lines.isEmpty()) false else {
                    val half = cfg.wheelRows / 2f + 0.6f
                    val rh = rowH()
                    bdTarget.set(20 * dp, h * cfg.verticalPos - rh * half, w - 20 * dp, h * cfg.verticalPos + rh * half)
                    true
                }
            }
            2 -> {
                val l = curLayout
                if (l == null) false else {
                    val tw = textW(l)
                    bdTarget.set(popX - tw / 2 - padX, popY - l.height / 2f - padY, popX + tw / 2 + padX, popY + l.height / 2f + padY)
                    true
                }
            }
            else -> {
                val l = curLayout
                if (l == null) false else {
                    val baseY = h * cfg.verticalPos
                    val nl = nextLayout
                    val tw = max(textW(l), if (nl != null) textW(nl) else 0f)
                    val top = baseY - l.height / 2f
                    val bottom = if (nl != null) baseY + l.height / 2f + 14 * dp + nl.height else baseY + l.height / 2f
                    bdTarget.set(w / 2f - tw / 2f - padX, top - padY, w / 2f + tw / 2f + padX, bottom + padY)
                    true
                }
            }
        }
    }

    private fun drawBackdrop(c: Canvas) {
        if (!cfg.bdShow) {
            bdVis = 0f
            return
        }
        val has = computeBackdropTarget()
        val goal = if (has) 1f else 0f
        var moving = false
        if (has) {
            if (bdRect.isEmpty) bdRect.set(bdTarget)
            fun lerp(a: Float, b: Float): Float {
                val v = a + (b - a) * 0.28f
                return if (abs(v - b) <= 0.5f) b else { moving = true; v }
            }
            bdRect.set(
                lerp(bdRect.left, bdTarget.left), lerp(bdRect.top, bdTarget.top),
                lerp(bdRect.right, bdTarget.right), lerp(bdRect.bottom, bdTarget.bottom)
            )
        }
        if (abs(goal - bdVis) > 0.01f) {
            bdVis += (goal - bdVis) * 0.2f
            moving = true
        } else bdVis = goal
        if (bdVis > 0.01f && !bdRect.isEmpty) {
            val a = (cfg.bdAlpha * bdVis * 255).toInt().coerceIn(0, 255)
            val sh = bdShader
            val bmp = bdBlur
            if (cfg.bdMode == 2 && sh != null && bmp != null) {
                val rw = bdRect.width()
                val rh = bdRect.height()
                val sc = max(rw / bmp.width, rh / bmp.height)
                bdMatrix.setScale(sc, sc)
                bdMatrix.postTranslate(bdRect.left + (rw - bmp.width * sc) / 2f, bdRect.top + (rh - bmp.height * sc) / 2f)
                sh.setLocalMatrix(bdMatrix)
                bdPaint.shader = sh
                bdPaint.alpha = a
            } else {
                bdPaint.shader = null
                bdPaint.color = bdColor or 0xFF000000.toInt()
                bdPaint.alpha = a
            }
            c.drawRoundRect(bdRect, 18 * dp, 18 * dp, bdPaint)
        }
        if (moving) postInvalidateOnAnimation()
    }

    private fun drawClassic(c: Canvas, karaoke: Boolean) {
        val baseY = height * cfg.verticalPos
        val cx = width / 2f
        val p = progress
        val a = p.coerceIn(0f, 1f)
        val curH = curLayout?.height ?: 0
        oldLayout?.let {
            if (a < 1f) drawTf(c, if (karaoke) (oldHiLayout ?: it) else it, cx, baseY, exitTf(a), null, 0f)
        }
        curLayout?.let {
            drawTf(c, it, cx, baseY, enterTf(p), if (karaoke) curHiLayout else null, hp)
        }
        nextLayout?.let {
            val ny = baseY + curH / 2f + 14 * dp + it.height / 2f
            drawTf(c, it, cx, ny, Tf(alpha = a), null, 0f)
        }
    }

    private fun drawPopup(c: Canvas) {
        val p = progress
        val a = p.coerceIn(0f, 1f)
        oldLayout?.let { if (a < 1f) drawTf(c, it, oldX, oldY, exitTf(a), null, 0f, oldRot) }
        curLayout?.let { drawTf(c, it, popX, popY, enterTf(p), null, 0f, popRot) }
    }

    private fun pickPopup() {
        oldX = popX
        oldY = popY
        oldRot = popRot
        val l = curLayout ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val hw = textW(l) / 2f
        val hh = l.height / 2f
        var bestX = w / 2f
        var bestY = h / 2f
        var bestScore = -1f
        repeat(18) {
            val minX = hw + 12 * dp
            val maxX = w - hw - 12 * dp
            val x = if (maxX > minX) minX + rnd.nextFloat() * (maxX - minX) else w / 2f
            val minY = max(hh + 60 * dp, h * 0.08f)
            val maxY = minOf(h - hh - 90 * dp, h * 0.92f)
            val y = if (maxY > minY) minY + rnd.nextFloat() * (maxY - minY) else h / 2f
            val r = RectF(x - hw, y - hh, x + hw, y + hh)
            var ok = true
            for (f in cfg.frames) if (RectF.intersects(RectF(f.l * w, f.t * h, f.r * w, f.b * h), r)) ok = false
            if (cfg.hideCenter) {
                val bh = cfg.centerSize * h
                if (r.bottom > (h - bh) / 2f && r.top < (h + bh) / 2f) ok = false
            }
            val dist = hypot(x - oldX, y - oldY)
            val score = if (ok) dist + 100000f else dist
            if (score > bestScore) {
                bestScore = score
                bestX = x
                bestY = y
            }
        }
        popX = bestX
        popY = bestY
        popRot = if (cfg.popupTilt) (rnd.nextFloat() - 0.5f) * 12f else 0f
    }

    private fun wheelLayout(k: Int): StaticLayout? {
        wheelCache.get(k)?.let { return it }
        if (k !in lines.indices) return null
        val txt = lines[k].text.ifBlank { "♪" }
        val l = layoutOf(txt, wheelPaint, 1f, 2) ?: return null
        wheelCache.put(k, l)
        return l
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    private fun drawWheel(c: Canvas) {
        if (lines.isEmpty()) return
        val half = cfg.wheelRows / 2
        val baseY = height * cfg.verticalPos
        val rh = rowH()
        val first = floor(wheelPos).toInt() - half - 1
        val last = ceil(wheelPos).toInt() + half + 1
        for (k in first..last) {
            if (k !in lines.indices) continue
            val d = k - wheelPos
            val ad = abs(d)
            if (ad > half + 0.6f) continue
            val sc = (1f - cfg.wheelSpread * ad).coerceAtLeast(0.35f)
            val edge = ((half + 0.6f - ad) / 0.6f).coerceIn(0f, 1f)
            val alpha = (1f - 0.42f * ad).coerceIn(0.08f, 1f) * edge
            val l = wheelLayout(k) ?: continue
            wheelPaint.color = blend(mainColor, hiColor, 1f - ad)
            drawTf(c, l, width / 2f, baseY + d * rh, Tf(alpha = alpha, sx = sc, sy = sc), null, 0f)
        }
    }

    // ------------------------------------------------------------------ редактор рамок

    private fun drawEdit(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(0x66000000)
        if (cfg.hideCenter) {
            val bh = cfg.centerSize * h
            fill.color = 0x33FFFFFF
            c.drawRect(0f, (h - bh) / 2f, w, (h + bh) / 2f, fill)
            ui.textSize = 12 * dp
            c.drawText("центр без текста", w / 2f, h / 2f, ui)
        }
        editFrames.forEachIndexed { i, f ->
            val r = RectF(f.left * w, f.top * h, f.right * w, f.bottom * h)
            fill.color = 0x55FF4D6D
            c.drawRoundRect(r, 12 * dp, 12 * dp, fill)
            stroke.color = if (i == selected) Color.WHITE else 0xFFFF4D6D.toInt()
            stroke.strokeWidth = (if (i == selected) 3 else 2) * dp
            c.drawRoundRect(r, 12 * dp, 12 * dp, stroke)
            fill.color = Color.WHITE
            c.drawCircle(r.right, r.bottom, 11 * dp, fill)
            ui.textSize = 13 * dp
            c.drawText("без текста", r.centerX(), r.centerY() + 5 * dp, ui)
        }
        ui.textSize = 14 * dp
        c.drawText("Тащи рамку · угол — размер", w / 2f, 96 * dp, ui)
        for (i in 0..2) {
            val r = btnRects[i]
            val disabled = i == 1 && selected !in editFrames.indices
            fill.color = when {
                i == 2 -> 0xFF8B6CFF.toInt()
                disabled -> 0x66262636
                else -> 0xEE262636.toInt()
            }
            c.drawRoundRect(r, 23 * dp, 23 * dp, fill)
            ui.textSize = 14 * dp
            ui.alpha = if (disabled) 100 else 255
            c.drawText(btnLabels[i], r.centerX(), r.centerY() - (ui.ascent() + ui.descent()) / 2f, ui)
            ui.alpha = 255
        }
    }

    private fun layoutButtons() {
        val bw = 108 * dp
        val bh = 46 * dp
        val gap = 10 * dp
        val total = 3 * bw + 2 * gap
        var x = (width - total) / 2f
        val y = height - 150 * dp
        for (i in 0..2) {
            btnRects[i].set(x, y, x + bw, y + bh)
            x += bw + gap
        }
    }

    fun enterEdit(frames: List<Frame>, done: (List<Frame>) -> Unit) {
        editFrames.clear()
        frames.forEach { editFrames += RectF(it.l, it.t, it.r, it.b) }
        selected = -1
        dragMode = 0
        editMode = true
        onDone = done
        layoutButtons()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editMode) return false
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                for (i in 0..2) {
                    if (btnRects[i].contains(x, y)) {
                        onButton(i)
                        return true
                    }
                }
                dragMode = 0
                lastX = x
                lastY = y
                for (i in editFrames.indices.reversed()) {
                    val f = editFrames[i]
                    if (hypot(x - f.right * width, y - f.bottom * height) < 30 * dp) {
                        selected = i; dragMode = 2; invalidate(); return true
                    }
                    if (x >= f.left * width && x <= f.right * width && y >= f.top * height && y <= f.bottom * height) {
                        selected = i; dragMode = 1; invalidate(); return true
                    }
                }
                selected = -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (selected in editFrames.indices && dragMode != 0) {
                    val f = editFrames[selected]
                    val dx = (x - lastX) / width
                    val dy = (y - lastY) / height
                    if (dragMode == 1) {
                        f.offset(dx.coerceIn(-f.left, 1f - f.right), dy.coerceIn(-f.top, 1f - f.bottom))
                    } else {
                        f.right = (f.right + dx).coerceIn(f.left + 0.1f, 1f)
                        f.bottom = (f.bottom + dy).coerceIn(f.top + 0.06f, 1f)
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = 0
        }
        return true
    }

    private fun onButton(i: Int) {
        when (i) {
            0 -> {
                editFrames += RectF(0.15f, 0.4f, 0.85f, 0.55f)
                selected = editFrames.lastIndex
            }
            1 -> if (selected in editFrames.indices) {
                editFrames.removeAt(selected)
                selected = -1
            }
            2 -> {
                val res = editFrames.map { Frame(it.left, it.top, it.right, it.bottom) }
                editMode = false
                selected = -1
                val cb = onDone
                onDone = null
                cb?.invoke(res)
            }
        }
        invalidate()
    }
}
