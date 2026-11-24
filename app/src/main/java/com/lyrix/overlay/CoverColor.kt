package com.lyrix.overlay

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

object CoverColor {
    /** Яркий цвет из обложки, подтянутый по яркости, чтобы текст читался. */
    fun pick(bmp: Bitmap): Int {
        return try {
            val p = Palette.from(bmp).maximumColorCount(16).generate()
            val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
                ?: return Color.WHITE
            val hsv = FloatArray(3)
            Color.colorToHSV(sw.rgb, hsv)
            hsv[1] = hsv[1].coerceAtMost(0.85f)
            hsv[2] = hsv[2].coerceAtLeast(0.88f)
            Color.HSVToColor(hsv)
        } catch (e: Exception) {
            Color.WHITE
        }
    }

    /** Контрастный (противоположный по оттенку) цвет к данному. */
    fun contrastOf(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + 180f) % 360f
        hsv[1] = hsv[1].coerceIn(0.55f, 0.9f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }
}
