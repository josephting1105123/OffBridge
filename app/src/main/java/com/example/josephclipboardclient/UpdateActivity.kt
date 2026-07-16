package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.platform.ComposeView
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.UpdateScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme

/**
 * Standalone host for the "Updates" page, reached from the power-menu Updates tile. Mirrors
 * [WindowsVersionActivity]: restore the saved day/night mode before setting content, then host one
 * Compose screen via a raw ComposeView (AppTheme reads isSystemInDarkTheme(), which AppCompatDelegate
 * drives, so day/night stays in lockstep with the rest of the app).
 */
class UpdateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", "System Default")
        val nightMode = when (savedTheme) {
            "Light" -> AppCompatDelegate.MODE_NIGHT_NO
            "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        setContentView(ComposeView(this).apply {
            setContent { AppTheme { UpdateScreen() } }
        })
    }
}
