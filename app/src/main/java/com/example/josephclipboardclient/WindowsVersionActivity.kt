package com.EntropicSoftwareLab.ClipboardShare

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.WindowsVersionScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme

class WindowsVersionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FileBridgePrefs", android.content.Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", "System Default")
        val nightMode = when (savedTheme) {
            "Light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "Dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)

        // First XML→Compose screen, hosted via ComposeView (interop — no activity-compose needed).
        // AppTheme reads isSystemInDarkTheme(), which AppCompatDelegate above drives, so day/night
        // stays in lockstep with the rest of the (still-XML) app.
        setContentView(ComposeView(this).apply {
            setContent { AppTheme { WindowsVersionScreen() } }
        })
    }
}