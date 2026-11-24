package com.lyrix.overlay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Рамка, в которой текст НЕ показывается. Координаты — доли экрана (0..1). */
data class Frame(val l: Float, val t: Float, val r: Float, val b: Float)

data class Config(
    // стиль субтитров: 0 классика, 1 караоке, 2 поп-ап, 3 колесо
    val subStyle: Int = 0,
    // цвет текста: 0 свой, 1 цвет обложки, 2 контрастный к обложке
    val colorMode: Int = 0,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    // караоке: цвет подсветки
    val hiMode: Int = 1,
    val hiColor: Int = 0xFFFFE066.toInt(),
    val kDim: Float = 0.55f,
    val kWordSnap: Boolean = false,
    val textSizeSp: Float = 28f,
    val verticalPos: Float = 0.78f,
    // анимация появления 0..11
    val anim: Int = 1,
    val animMs: Int = 600,
    val showNext: Boolean = true,
    val textAlpha: Float = 1f,
    // делить строку на части по N слов (0 — целиком)
    val splitWords: Int = 0,
    // подложка: режим 0 цвет, 1 цвет обложки, 2 размытая обложка
    val bdShow: Boolean = false,
    val bdMode: Int = 0,
    val bdColor: Int = 0xFF000000.toInt(),
    val bdAlpha: Float = 0.45f,
    // офлайн-режим: ничего не качать из интернета
    val offlineMode: Boolean = false,
    val popupTilt: Boolean = true,
    val wheelRows: Int = 5,
    val wheelSpread: Float = 0.14f,
    val hideCenter: Boolean = false,
    val centerSize: Float = 0.25f,
    val offsetMs: Int = 0,
    val frames: List<Frame> = emptyList(),
    // --- локальное (не уходит в файл настроек) ---
    val fontPath: String? = null,
    val fontName: String? = null,
    // кнопка-оверлей
    val bubbleShow: Boolean = true,
    val bubbleMode: Int = 1, // 0 иконка, 1 обложка
    val bubbleIcon: String = "note",
    val bubbleIconPath: String? = null,
    val bubbleSize: Int = 56,
    val bubbleOpensMenu: Boolean = true,
    val bubbleSpin: Boolean = true,
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,
    val appIcon: String = "default",
    val devUnlocked: Boolean = false
)

fun Config.toJson(export: Boolean): JSONObject {
    val o = JSONObject()
    o.put("subStyle", subStyle)
    o.put("colorMode", colorMode)
    o.put("textColor", textColor)
    o.put("hiMode", hiMode)
    o.put("hiColor", hiColor)
    o.put("kDim", kDim.toDouble())
    o.put("kWordSnap", kWordSnap)
    o.put("textSizeSp", textSizeSp.toDouble())
    o.put("verticalPos", verticalPos.toDouble())
    o.put("anim", anim)
    o.put("animMs", animMs)
    o.put("showNext", showNext)
    o.put("textAlpha", textAlpha.toDouble())
    o.put("splitWords", splitWords)
    o.put("bdShow", bdShow)
    o.put("bdMode", bdMode)
    o.put("bdColor", bdColor)
    o.put("bdAlpha", bdAlpha.toDouble())
    o.put("offlineMode", offlineMode)
    o.put("popupTilt", popupTilt)
    o.put("wheelRows", wheelRows)
    o.put("wheelSpread", wheelSpread.toDouble())
    o.put("hideCenter", hideCenter)
    o.put("centerSize", centerSize.toDouble())
    o.put("offsetMs", offsetMs)
    o.put("bubbleShow", bubbleShow)
    o.put("bubbleMode", bubbleMode)
    o.put("bubbleIcon", if (bubbleIcon == "custom" && export) "note" else bubbleIcon)
    o.put("bubbleSize", bubbleSize)
    o.put("bubbleOpensMenu", bubbleOpensMenu)
    o.put("bubbleSpin", bubbleSpin)
    val fr = JSONArray()
    frames.forEach {
        fr.put(JSONArray().put(it.l.toDouble()).put(it.t.toDouble()).put(it.r.toDouble()).put(it.b.toDouble()))
    }
    o.put("frames", fr)
    if (!export) {
        o.put("fontPath", fontPath ?: JSONObject.NULL)
        o.put("fontName", fontName ?: JSONObject.NULL)
        o.put("bubbleIconPath", bubbleIconPath ?: JSONObject.NULL)
        o.put("bubbleX", bubbleX)
        o.put("bubbleY", bubbleY)
        o.put("appIcon", appIcon)
        o.put("devUnlocked", devUnlocked)
    }
    return o
}

fun configFromJson(o: JSONObject, b: Config, local: Boolean): Config {
    fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
    fun s(k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
    val frames = try {
        if (o.has("frames")) {
            val arr = o.getJSONArray("frames")
            (0 until arr.length()).map {
                val a = arr.getJSONArray(it)
                Frame(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
            }
        } else b.frames
    } catch (e: Exception) {
        b.frames
    }
    var c = b.copy(
        subStyle = o.optInt("subStyle", b.subStyle).coerceIn(0, 3),
        colorMode = o.optInt("colorMode", b.colorMode).coerceIn(0, 2),
        textColor = o.optInt("textColor", b.textColor),
        hiMode = o.optInt("hiMode", b.hiMode).coerceIn(0, 2),
        hiColor = o.optInt("hiColor", b.hiColor),
        kDim = f("kDim", b.kDim).coerceIn(0.2f, 1f),
        kWordSnap = o.optBoolean("kWordSnap", b.kWordSnap),
        textSizeSp = f("textSizeSp", b.textSizeSp).coerceIn(12f, 64f),
        verticalPos = f("verticalPos", b.verticalPos).coerceIn(0.05f, 0.97f),
        anim = o.optInt("anim", b.anim).coerceIn(0, 11),
        animMs = o.optInt("animMs", b.animMs).coerceIn(150, 2000),
        showNext = o.optBoolean("showNext", b.showNext),
        textAlpha = f("textAlpha", b.textAlpha).coerceIn(0.1f, 1f),
        splitWords = o.optInt("splitWords", b.splitWords).coerceIn(0, 8),
        bdShow = o.optBoolean("bdShow", b.bdShow),
        bdMode = o.optInt("bdMode", b.bdMode).coerceIn(0, 2),
        bdColor = o.optInt("bdColor", b.bdColor),
        bdAlpha = f("bdAlpha", b.bdAlpha).coerceIn(0f, 1f),
        offlineMode = o.optBoolean("offlineMode", b.offlineMode),
        popupTilt = o.optBoolean("popupTilt", b.popupTilt),
        wheelRows = o.optInt("wheelRows", b.wheelRows).coerceIn(3, 9),
        wheelSpread = f("wheelSpread", b.wheelSpread).coerceIn(0f, 0.3f),
        hideCenter = o.optBoolean("hideCenter", b.hideCenter),
        centerSize = f("centerSize", b.centerSize).coerceIn(0.05f, 0.8f),
        offsetMs = o.optInt("offsetMs", b.offsetMs).coerceIn(-5000, 5000),
        frames = frames,
        bubbleShow = o.optBoolean("bubbleShow", b.bubbleShow),
        bubbleMode = o.optInt("bubbleMode", b.bubbleMode).coerceIn(0, 1),
        bubbleIcon = o.optString("bubbleIcon", b.bubbleIcon),
        bubbleSize = o.optInt("bubbleSize", b.bubbleSize).coerceIn(36, 110),
        bubbleOpensMenu = o.optBoolean("bubbleOpensMenu", b.bubbleOpensMenu),
        bubbleSpin = o.optBoolean("bubbleSpin", b.bubbleSpin)
    )
    if (local) {
        c = c.copy(
            fontPath = s("fontPath")?.takeIf { File(it).exists() },
            fontName = s("fontName"),
            bubbleIconPath = s("bubbleIconPath")?.takeIf { File(it).exists() },
            bubbleX = o.optInt("bubbleX", -1),
            bubbleY = o.optInt("bubbleY", -1),
            appIcon = o.optString("appIcon", "default"),
            devUnlocked = o.optBoolean("devUnlocked", false)
        )
        if (c.bubbleIcon == "custom" && c.bubbleIconPath == null) c = c.copy(bubbleIcon = "note")
    }
    return c
}

object Prefs {
    private var sp: SharedPreferences? = null
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("lyrix", Context.MODE_PRIVATE)
        sp = s
        val raw = s.getString("cfg", null)
        if (raw != null) {
            try {
                _config.value = configFromJson(JSONObject(raw), Config(), true)
            } catch (e: Exception) {
            }
        }
    }

    fun update(block: (Config) -> Config) {
        _config.update(block)
        sp?.edit()?.putString("cfg", _config.value.toJson(false).toString())?.apply()
    }

    fun exportJson(): String {
        val o = _config.value.toJson(true)
        o.put("_app", "Lyrix")
        o.put("_format", 2)
        return o.toString(2)
    }

    fun importJson(text: String): Boolean {
        return try {
            val o = JSONObject(text)
            update { configFromJson(o, it, false) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun reset() {
        update { Config(fontPath = it.fontPath, fontName = it.fontName, appIcon = it.appIcon, devUnlocked = it.devUnlocked) }
    }
}
