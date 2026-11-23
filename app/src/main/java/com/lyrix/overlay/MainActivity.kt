package com.lyrix.overlay

import android.Manifest
import android.content.pm.PackageManager
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
            LyrixTheme {
                MainScreen(
                    perms = perms.value,
                    onPickFont = { fontPicker.launch(arrayOf("*/*")) },
                    onResetFont = { resetFont() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        perms.value = PermState(
            overlay = Settings.canDrawOverlays(this),
            listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        )
    }

    private fun importFont(uri: Uri) {
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "custom font"
            filesDir.listFiles()?.filter { it.name.startsWith("font_") }?.forEach { it.delete() }
            val out = File(filesDir, "font_${System.currentTimeMillis()}.ttf")
            contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            }
            Typeface.createFromFile(out)
            Prefs.update { it.copy(fontPath = out.absolutePath, fontName = name) }
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить шрифт", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetFont() {
        filesDir.listFiles()?.filter { it.name.startsWith("font_") }?.forEach { it.delete() }
        Prefs.update { it.copy(fontPath = null, fontName = null) }
    }
}
