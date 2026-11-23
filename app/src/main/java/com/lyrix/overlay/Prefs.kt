package com.lyrix.overlay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File

/** Рамка, в которой текст НЕ показывается. Координаты — доли экрана (0..1). */
data class Frame(val l: Float, val t: Float, val r: Float, val b: Float)

data class Config(
    val colorFromCover: Boolean = false,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textSizeSp: Float = 28f,
    val verticalPos: Float = 0.78f,
    val animStyle: Int = 0,          // 0 подъём, 1 зум, 2 fade, 3 pop
    val hideCenter: Boolean = false,
    val centerSize: Float = 0.25f,   // высота центральной полосы без текста (доля экрана)
    val showNext: Boolean = true,
    val offsetMs: Int = 0,
    val playerCard: Boolean = true,
    val playerX: Int = -1,
    val playerY: Int = -1,
    val fontPath: String? = null,
    val fontName: String? = null,
    val frames: List<Frame> = emptyList()
)

object Prefs {
    private var sp: SharedPreferences? = null
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("lyrix", Context.MODE_PRIVATE)
        sp = s
        _config.value = load(s)
    }

    fun update(block: (Config) -> Config) {
        _config.update(block)
        sp?.let { save(it, _config.value) }
    }

    private fun load(s: SharedPreferences): Config {
        val d = Config()
        val frames = try {
            val arr = JSONArray(s.getString("frames", "[]"))
            (0 until arr.length()).map {
                val a = arr.getJSONArray(it)
                Frame(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
            }
        } catch (e: Exception) {
            emptyList()
        }
        return Config(
            colorFromCover = s.getBoolean("colorFromCover", d.colorFromCover),
            textColor = s.getInt("textColor", d.textColor),
            textSizeSp = s.getFloat("textSizeSp", d.textSizeSp),
            verticalPos = s.getFloat("verticalPos", d.verticalPos),
            animStyle = s.getInt("animStyle", d.animStyle),
            hideCenter = s.getBoolean("hideCenter", d.hideCenter),
            centerSize = s.getFloat("centerSize", d.centerSize),
            showNext = s.getBoolean("showNext", d.showNext),
            offsetMs = s.getInt("offsetMs", d.offsetMs),
            playerCard = s.getBoolean("playerCard", d.playerCard),
            playerX = s.getInt("playerX", d.playerX),
            playerY = s.getInt("playerY", d.playerY),
            fontPath = s.getString("fontPath", null)?.takeIf { File(it).exists() },
            fontName = s.getString("fontName", null),
            frames = frames
        )
    }

    private fun save(s: SharedPreferences, c: Config) {
        val arr = JSONArray()
        c.frames.forEach {
            arr.put(JSONArray().put(it.l.toDouble()).put(it.t.toDouble()).put(it.r.toDouble()).put(it.b.toDouble()))
        }
        s.edit()
            .putBoolean("colorFromCover", c.colorFromCover)
            .putInt("textColor", c.textColor)
            .putFloat("textSizeSp", c.textSizeSp)
            .putFloat("verticalPos", c.verticalPos)
            .putInt("animStyle", c.animStyle)
            .putBoolean("hideCenter", c.hideCenter)
            .putFloat("centerSize", c.centerSize)
            .putBoolean("showNext", c.showNext)
            .putInt("offsetMs", c.offsetMs)
            .putBoolean("playerCard", c.playerCard)
            .putInt("playerX", c.playerX)
            .putInt("playerY", c.playerY)
            .putString("fontPath", c.fontPath)
            .putString("fontName", c.fontName)
            .putString("frames", arr.toString())
            .apply()
    }
}
