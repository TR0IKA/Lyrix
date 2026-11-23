package com.lyrix.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.hypot

/**
 * Полноэкранный прозрачный вид: рисует строку текста, не показывает её в рамках
 * и (опционально) в центральной полосе. В режиме редактирования — двигаем рамки.
 */
class LyricsView(ctx: Context) : View(ctx) {

    private val dp = resources.displayMetrics.density
    private var cfg = Config()

    private val curPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val nextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var cur = ""
    private var next = ""
    private var curLayout: StaticLayout? = null
    private var oldLayout: StaticLayout? = null
    private var nextLayout: StaticLayout? = null
    private var progress = 1f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 550
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    // ---------- режим редактирования ----------
    private var editMode = false
    private val editFrames = mutableListOf<RectF>()
    private var selected = -1
    private var dragMode = 0 // 0 нет, 1 двигаем, 2 меняем размер
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

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun applyConfig(c: Config, tf: Typeface?) {
        cfg = c
        val t = tf ?: Typeface.DEFAULT_BOLD
        curPaint.typeface = t
        nextPaint.typeface = t
        curPaint.textSize = sp(c.textSizeSp)
        nextPaint.textSize = sp(c.textSizeSp * 0.62f)
        val r = 6 * dp
        curPaint.setShadowLayer(r, 0f, 2 * dp, 0xB3000000.toInt())
        nextPaint.setShadowLayer(r, 0f, 2 * dp, 0xB3000000.toInt())
        curLayout = layoutOf(cur, curPaint)
        nextLayout = layoutOf(next, nextPaint)
        invalidate()
    }

    fun setTextColor(c: Int) {
        curPaint.color = c
        nextPaint.color = (c and 0x00FFFFFF) or (0xA0 shl 24)
        invalidate()
    }

    fun setLines(c: String, n: String) {
        if (c != cur) {
            oldLayout = curLayout
            cur = c
            next = n
            curLayout = layoutOf(cur, curPaint)
            nextLayout = layoutOf(next, nextPaint)
            startAnim()
        } else if (n != next) {
            next = n
            nextLayout = layoutOf(next, nextPaint)
            invalidate()
        }
    }

    private fun startAnim() {
        animator.cancel()
        animator.interpolator =
            if (cfg.animStyle == 3) OvershootInterpolator(1.8f) else DecelerateInterpolator(1.6f)
        animator.duration = if (cfg.animStyle == 2) 400 else 550
        animator.start()
    }

    private fun layoutOf(text: String, p: TextPaint): StaticLayout? {
        if (text.isEmpty() || width <= 0) return null
        val w = (width - 48 * dp).toInt().coerceAtLeast(100)
        return StaticLayout.Builder.obtain(text, 0, text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .build()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        curLayout = layoutOf(cur, curPaint)
        nextLayout = layoutOf(next, nextPaint)
        oldLayout = null
        layoutButtons()
    }

    // ---------- рисование ----------
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
        drawText(canvas)
        canvas.restore()
    }

    private fun drawText(c: Canvas) {
        val baseY = height * cfg.verticalPos
        val p = progress
        val a = p.coerceIn(0f, 1f)
        val travel = 34 * dp
        val style = cfg.animStyle
        val curH = curLayout?.height ?: 0

        oldLayout?.let {
            if (a < 1f) {
                val dy = if (style == 0) -travel * a else 0f
                val sc = if (style == 1) 1f + 0.12f * a else 1f
                drawLayout(c, it, baseY + dy, 1f - a, sc)
            }
        }
        curLayout?.let {
            val dy = if (style == 0) (1f - p) * travel else 0f
            val sc = when (style) {
                1 -> 0.82f + 0.18f * p
                3 -> 0.55f + 0.45f * p
                else -> 1f
            }
            drawLayout(c, it, baseY + dy, a, sc)
        }
        nextLayout?.let {
            val ny = baseY + curH / 2f + 14 * dp + it.height / 2f
            val dy = if (style == 0) (1f - p) * travel * 0.6f else 0f
            drawLayout(c, it, ny + dy, a, 1f)
        }
    }

    private fun drawLayout(c: Canvas, l: StaticLayout, cy: Float, alpha: Float, scale: Float) {
        if (alpha <= 0.01f) return
        val count = if (alpha < 0.99f)
            c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        else c.save()
        c.translate(width / 2f, cy)
        c.scale(scale, scale)
        c.translate(-l.width / 2f, -l.height / 2f)
        l.draw(c)
        c.restoreToCount(count)
    }

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

    // ---------- редактор рамок ----------
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
                    val hx = f.right * width
                    val hy = f.bottom * height
                    if (hypot(x - hx, y - hy) < 30 * dp) {
                        selected = i
                        dragMode = 2
                        invalidate()
                        return true
                    }
                    if (x >= f.left * width && x <= f.right * width && y >= f.top * height && y <= f.bottom * height) {
                        selected = i
                        dragMode = 1
                        invalidate()
                        return true
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
