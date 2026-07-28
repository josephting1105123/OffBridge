package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SplashScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import com.EntropicSoftwareLab.ClipboardShare.update.UpdateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

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

        // Recover clipboard + transfer history from the save-folder backup if the local stores are
        // empty (e.g. after a "Clear data" while the folder grant is still held). Runs on IO; the
        // ~2s splash window covers the small read before MainActivity binds the history UI.
        HistoryBackupManager.maybeRestoreOnLaunch(applicationContext)

        // First launch after the rename: move already-received photos/videos out of the old
        // Pictures|Movies/"Clipboard Share" album into "OffBridge" so they aren't split in two.
        // Own background thread, no-op after it has run once, never fails launch.
        MediaFolderMigration.runOnce(applicationContext)

        // Pre-warm the update check in the background at launch, so opening the Updates page shows the
        // result after the loader's 1s floor instead of waiting on a fresh GitHub round-trip. Silent —
        // no popup/badge; the Updates page reads UpdateRepository.status.
        UpdateRepository.check(applicationContext, force = false)

        // Compose splash (logo + name + pulsing dots); navigation below is unchanged.
        setContentView(ComposeView(this).apply {
            setContent { AppTheme { SplashScreen() } }
        })

        // Wait a bit to simulate loading interfaces, then navigate
        lifecycleScope.launch {
            delay(2000) // 2 seconds loading screen
            val hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding_v2", false)
            if (hasCompletedOnboarding) {
                val mainIntent = Intent(this@SplashActivity, MainActivity::class.java)
                intent.extras?.let { mainIntent.putExtras(it) }
                startActivity(mainIntent)
                overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
            } else {
                startActivity(Intent(this@SplashActivity, OnboardingActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
            }
            finish()
        }
    }
}
