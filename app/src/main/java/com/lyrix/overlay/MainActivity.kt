package com.lyrix.overlay

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import java.io.File

class MainActivity : ComponentActivity() {

    private val perms = mutableStateOf(PermState())

    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFont(it) } }
    private val bubblePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importBubbleIcon(it) } }
    private val shortcutPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { makeShortcut(it) } }
    private val exporter =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportTo(it) } }
    private val importer =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFrom(it) } }
    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SpaceTheme {
                MainScreen(
                    perms = perms.value,
                    actions = MainActions(
                        pickFont = { fontPicker.launch(arrayOf("*/*")) },
                        resetFont = { resetFont() },
                        pickBubbleIcon = { bubblePicker.launch("image/*") },
                        pickShortcutIcon = { shortcutPicker.launch("image/*") },
                        exportCfg = { exporter.launch("lyrix-settings.json") },
                        importCfg = { importer.launch(arrayOf("*/*")) }
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        perms.value = PermState(overlay = Settings.canDrawOverlays(this), listener = listener)
        if (listener && !MediaListener.connected.value) MediaListener.rebind(this)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun importFont(uri: Uri) {
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "custom font"
            filesDir.listFiles()?.filter { it.name.startsWith("font_") }?.forEach { it.delete() }
            val out = File(filesDir, "font_${System.currentTimeMillis()}.ttf")
            contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { o -> input.copyTo(o) } }
            Typeface.createFromFile(out)
            Prefs.update { it.copy(fontPath = out.absolutePath, fontName = name) }
        } catch (e: Exception) {
            toast("Не удалось загрузить шрифт")
        }
    }

    private fun resetFont() {
        filesDir.listFiles()?.filter { it.name.startsWith("font_") }?.forEach { it.delete() }
        Prefs.update { it.copy(fontPath = null, fontName = null) }
    }

    private fun importBubbleIcon(uri: Uri) {
        val bmp = ImageUtil.loadSquare(this, uri, 256)
        if (bmp == null) {
            toast("Не удалось открыть картинку")
            return
        }
        filesDir.listFiles()?.filter { it.name.startsWith("bubble_") }?.forEach { it.delete() }
        val f = File(filesDir, "bubble_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Prefs.update { it.copy(bubbleIcon = "custom", bubbleIconPath = f.absolutePath) }
    }

    private fun makeShortcut(uri: Uri) {
        val bmp = ImageUtil.loadSquare(this, uri, 192)
        if (bmp == null) {
            toast("Не удалось открыть картинку")
            return
        }
        if (!ImageUtil.pinShortcut(this, bmp)) toast("Лаунчер не поддерживает ярлыки")
    }

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(Prefs.exportJson().toByteArray()) }
            toast("Настройки сохранены")
        } catch (e: Exception) {
            toast("Не удалось сохранить файл")
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            toast(if (Prefs.importJson(text)) "Настройки загружены" else "Файл не похож на настройки Lyrix")
        } catch (e: Exception) {
            toast("Не удалось прочитать файл")
        }
    }
}
