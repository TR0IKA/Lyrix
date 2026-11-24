package com.lyrix.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.net.Uri

/** Иконки для кнопки-оверлея. */
object BubbleIcons {
    val all = listOf("note", "rocket", "planet", "moon", "star", "headphones", "heart", "bolt")

    fun res(name: String): Int = when (name) {
        "rocket" -> R.drawable.ic_b_rocket
        "planet" -> R.drawable.ic_b_planet
        "moon" -> R.drawable.ic_b_moon
        "star" -> R.drawable.ic_b_star
        "headphones" -> R.drawable.ic_b_headphones
        "heart" -> R.drawable.ic_b_heart
        "bolt" -> R.drawable.ic_b_bolt
        else -> R.drawable.ic_b_note
    }
}

/** Смена значка приложения через activity-alias. */
object AppIcons {
    val names = listOf("default", "rocket", "planet", "moon", "star", "headphones")

    fun preview(name: String): Int = BubbleIcons.res(if (name == "default") "note" else name)

    fun bg(name: String): Long = when (name) {
        "rocket" -> 0xFF7A2CF0
        "planet" -> 0xFF0F6BD8
        "moon" -> 0xFF2B2A66
        "star" -> 0xFFD9368B
        "headphones" -> 0xFF0E8F9A
        else -> 0xFF6C4DFF
    }

    fun apply(ctx: Context, name: String) {
        val pm = ctx.packageManager
        names.sortedBy { it != name }.forEach { n ->
            val cls = "com.lyrix.overlay.alias." + n.replaceFirstChar { it.uppercase() }
            pm.setComponentEnabledSetting(
                ComponentName(ctx.packageName, cls),
                if (n == name) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}

object ImageUtil {
    /** Читает картинку, обрезает до квадрата и приводит к size x size. */
    fun loadSquare(ctx: Context, uri: Uri, size: Int): Bitmap? {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= size && opts.outHeight / (sample * 2) >= size) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: return null
            val side = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            return Bitmap.createScaledBitmap(sq, size, size, true)
        } catch (e: Exception) {
            return null
        }
    }

    fun circle(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawCircle(src.width / 2f, src.height / 2f, src.width / 2f, p)
        return out
    }

    /** Свой значок приложения: Android не даёт подменить иконку лаунчера, поэтому создаём ярлык на рабочем столе. */
    fun pinShortcut(ctx: Context, bmp: Bitmap): Boolean {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val info = ShortcutInfo.Builder(ctx, "lyrix_" + System.currentTimeMillis())
            .setShortLabel("Lyrix")
            .setIcon(Icon.createWithBitmap(circle(bmp)))
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
        return sm.requestPinShortcut(info, null)
    }
}
