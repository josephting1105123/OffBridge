package com.EntropicSoftwareLab.ClipboardShare

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone host for [SettingsFragment], launched from the power-options menu.
 *
 * Settings lives in its own activity (not MainActivity's container) so it never
 * desyncs bottomNavView.selectedItemId — system back simply returns to
 * MainActivity with its tab state untouched.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsFragmentContainer, SettingsFragment())
                .commit()
        }
    }
}
