package com.lyrix.overlay

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Плавающая карточка: обложка, название, назад / пауза / вперёд. Двигается пальцем. */
class PlayerCard(ctx: android.content.Context) : LinearLayout(ctx) {

    private val d = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    private val cover = ImageView(ctx)
    private val title = TextView(ctx)
    private val artist = TextView(ctx)
    private val prev = iconButton(R.drawable.ic_prev)
    private val play = iconButton(R.drawable.ic_play)
    private val next = iconButton(R.drawable.ic_next)
    private val playBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    }

    private var shownKey: String? = "__none__"
    private var shownArt: Bitmap? = null
    private var wasPlaying: Boolean? = null

    var wm: WindowManager? = null
    var lp: WindowManager.LayoutParams? = null
    var onMoved: ((Int, Int) -> Unit)? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    private fun iconButton(res: Int) = ImageButton(context).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(0x55FFFFFF),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        )
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(10), px(10), px(12), px(10))
        background = GradientDrawable().apply {
            cornerRadius = 24 * d
            setColor(0xEE15151F.toInt())
            setStroke(px(1), 0x22FFFFFF)
        }

        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setBackgroundColor(0xFF2A2A3A.toInt())
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 14 * d)
            }
        }
        cover.clipToOutline = true
        addView(cover, LinearLayout.LayoutParams(px(52), px(52)))

        val col = LinearLayout(context).apply { orientation = VERTICAL }
        title.apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        artist.apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(title)
        col.addView(artist)
        addView(col, LinearLayout.LayoutParams(px(120), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = px(10)
            rightMargin = px(4)
        })

        addView(prev, LinearLayout.LayoutParams(px(36), px(36)))
        play.background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), playBg, null)
        addView(play, LinearLayout.LayoutParams(px(42), px(42)).apply {
            leftMargin = px(2)
            rightMargin = px(2)
        })
        addView(next, LinearLayout.LayoutParams(px(36), px(36)))

        prev.setOnClickListener { NowPlaying.prev() }
        play.setOnClickListener { NowPlaying.playPause() }
        next.setOnClickListener { NowPlaying.next() }
    }

    fun animateIn() {
        alpha = 0f
        scaleX = 0.85f
        scaleY = 0.85f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    fun bind(t: Track?, playing: Boolean, accent: Int) {
        val key = t?.key
        if (key != shownKey) {
            shownKey = key
            val art = t?.art
            shownArt = art
            title.text = t?.title ?: "Ничего не играет"
            artist.text = t?.artist ?: ""
            cover.animate().alpha(0f).setDuration(120).withEndAction {
                if (art != null) cover.setImageBitmap(art) else cover.setImageDrawable(null)
                cover.animate().alpha(1f).setDuration(220).start()
            }.start()
        } else if (t?.art !== shownArt) {
            shownArt = t?.art
            cover.setImageBitmap(shownArt)
        }

        if (wasPlaying != playing) {
            wasPlaying = playing
            play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            play.scaleX = 0.7f
            play.scaleY = 0.7f
            play.animate().scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }
        playBg.setColor(accent)
        play.setColorFilter(if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = lp ?: return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = p.x
                startY = p.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = startX + (e.rawX - downRawX).toInt()
                p.y = startY + (e.rawY - downRawY).toInt()
                try {
                    wm?.updateViewLayout(this, p)
                } catch (_: Exception) {
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                onMoved?.invoke(p.x, p.y)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
