package com.EntropicSoftwareLab.ClipboardShare

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class PairingActivity : AppCompatActivity() {

    private val activityJob = Job()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

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
        
        setContentView(R.layout.activity_pairing)

        val targetName = intent.getStringExtra("TARGET_NAME") ?: "Unknown Device"
        val targetIp = intent.getStringExtra("TARGET_IP") ?: ""

        if (targetIp.isEmpty()) {
            finish()
            return
        }

        val tvPairingTitle = findViewById<TextView>(R.id.tvPairingTitle)
        val tvPairingSubtitle = findViewById<TextView>(R.id.tvPairingSubtitle)
        val pbPairingLoading = findViewById<ProgressBar>(R.id.pbPairingLoading)
        val llPairingContent = findViewById<LinearLayout>(R.id.llPairingContent)

        llPairingContent.visibility = View.GONE
        pbPairingLoading.visibility = View.VISIBLE
        tvPairingTitle.text = getString(R.string.pairing_requesting_title)
        tvPairingSubtitle.text = getString(R.string.pairing_waiting_subtitle, targetName)

        // Verification boxes stay hidden until the peer responds and the shared PIN is derived.
        findViewById<View>(R.id.tvPairingVerifyLabel).visibility = View.GONE
        findViewById<View>(R.id.pin_code_container).visibility = View.GONE

        activityScope.launch {
            val result = PairingDaemon.initiatePairing(
                applicationContext, targetIp, TransferDaemon.customDeviceName, targetName,
                onPin = { pin ->
                    runOnUiThread {
                        PinBox.bind(this@PairingActivity, pin)
                        findViewById<View>(R.id.tvPairingVerifyLabel).visibility = View.VISIBLE
                        findViewById<View>(R.id.pin_code_container).visibility = View.VISIBLE
                    }
                }
            )
            withContext(Dispatchers.Main) {
                when (result) {
                    "SUCCESS" -> {
                        // Name/id/key are persisted together by initiatePairing's saveTrustedNode.
                        Toast.makeText(applicationContext, getString(R.string.pairing_success, targetName), Toast.LENGTH_LONG).show()
                        // THE FIX: Use the raw string action instead of the reference to the deleted ClipboardActivity
                        val refreshIntent = Intent("com.example.josephclipboardclient.PAIRING_SUCCESSFUL").setPackage(packageName)
                        sendBroadcast(refreshIntent)
                    }
                    "DECLINED" -> {
                        Toast.makeText(applicationContext, getString(R.string.pairing_declined, targetName), Toast.LENGTH_LONG).show()
                    }
                    "TIMEOUT" -> {
                        Toast.makeText(applicationContext, getString(R.string.pairing_timed_out), Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(applicationContext, getString(R.string.pairing_failed), Toast.LENGTH_LONG).show()
                    }
                }
                delay(500)
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel()
    }
}