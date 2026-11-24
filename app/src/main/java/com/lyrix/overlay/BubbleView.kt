package com.lyrix.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.hypot

/**
 * Плавающая кнопка: иконка или обложка (крутится как пластинка). Тап открывает меню, перетаскивание — двигает.
 */
class BubbleView(ctx: Context) : FrameLayout(ctx) {

    private val d = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    private val ring = View(ctx)
    private val face = ImageView(ctx)

    var wm: WindowManager? = null
    var lp: WindowManager.LayoutParams? = null
    var onMoved: ((Int, Int) -> Unit)? = null
    var onClick: (() -> Unit)? = null

    private var sizePx = 0
    private var shownKey: String? = "__none__"
    private var shownBitmap: Bitmap? = null
    private var shownRes = 0
    private var customPath: String? = null
    private var customBmp: Bitmap? = null

    private val spinFace = ObjectAnimator.ofFloat(face, "rotation", 0f, 360f).apply {
        duration = 14000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
    }
    private val spinRing = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.SWEEP_GRADIENT
            colors = intArrayOf(0xFF9B7BFF.toInt(), 0xFF5EE6FF.toInt(), 0xFFFF6BD6.toInt(), 0xFF9B7BFF.toInt())
        }
        face.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF14122F.toInt())
        }
        face.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        face.clipToOutline = true
        addView(ring)
        addView(face)
    }

    fun animateIn() {
        alpha = 0f
        scaleX = 0.4f
        scaleY = 0.4f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun setSpin(a: ObjectAnimator, on: Boolean) {
        if (on) {
            if (!a.isStarted) a.start() else if (a.isPaused) a.resume()
        } else if (a.isStarted && a.isRunning) a.pause()
    }

    fun bind(c: Config, t: Track?, playing: Boolean, accent: Int) {
        val s = px(c.bubbleSize)
        if (s != sizePx) {
            sizePx = s
            ring.layoutParams = LayoutParams(s, s)
            face.layoutParams = LayoutParams(s - px(7), s - px(7)).apply { gravity = Gravity.CENTER }
            requestLayout()
        }
        if (c.bubbleIcon == "custom" && c.bubbleIconPath != null && c.bubbleIconPath != customPath) {
            customPath = c.bubbleIconPath
            customBmp = BitmapFactory.decodeFile(c.bubbleIconPath)
        }
        val cover = if (c.bubbleMode == 1) t?.art else null
        val custom = if (c.bubbleIcon == "custom") customBmp else null
        val bmp = cover ?: custom
        val photoMode = bmp != null
        if (photoMode) {
            if (shownBitmap !== bmp) {
                shownBitmap = bmp
                shownRes = 0
                face.clearColorFilter()
                face.setPadding(0, 0, 0, 0)
                face.scaleType = ImageView.ScaleType.CENTER_CROP
                face.setImageBitmap(bmp)
            }
        } else {
            val res = BubbleIcons.res(c.bubbleIcon)
            if (shownRes != res || shownBitmap != null) {
                shownBitmap = null
                shownRes = res
                face.scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (sizePx * 0.24f).toInt()
                face.setPadding(pad, pad, pad, pad)
                face.setImageResource(res)
            }
            face.setColorFilter(accent)
        }
        if (cover != null && t?.key != shownKey) {
            shownKey = t.key
            animate().scaleX(1.15f).scaleY(1.15f).setDuration(130).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }
        setSpin(spinFace, playing && photoMode && c.bubbleSpin)
        setSpin(spinRing, playing)
        if (!photoMode) face.rotation = 0f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = lp ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                startX = p.x
                startY = p.y
                dragging = false
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > 10 * d) dragging = true
                if (dragging) {
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    try {
                        wm?.updateViewLayout(this, p)
                    } catch (ex: Exception) {
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
                if (dragging) snap() else if (e.actionMasked == MotionEvent.ACTION_UP) onClick?.invoke()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** После отпускания прилипает к ближайшему краю экрана. */
    private fun snap() {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val target = if (p.x + width / 2 < dm.widthPixels / 2) px(6) else dm.widthPixels - width - px(6)
        p.y = p.y.coerceIn(0, maxOf(0, dm.heightPixels - height))
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 320
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                p.x = it.animatedValue as Int
                try {
                    wm?.updateViewLayout(this@BubbleView, p)
                } catch (ex: Exception) {
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onMoved?.invoke(p.x, p.y)
                }
            })
            start()
        }
    }
}
