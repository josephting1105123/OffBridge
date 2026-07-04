package com.EntropicSoftwareLab.ClipboardShare

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class PairingConfirmationActivity : AppCompatActivity() {

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
        
        setContentView(R.layout.activity_pairing_confirmation)

        val senderName = intent.getStringExtra("SENDER_NAME") ?: getString(R.string.pairing_unknown_device)
        val senderIp = intent.getStringExtra("SENDER_IP")
        val aesKey = intent.getByteArrayExtra("AES_KEY")
        val visualPin = intent.getStringExtra("VISUAL_PIN")
        val expiresAt = intent.getLongExtra(PairingDaemon.EXTRA_EXPIRES_AT, 0L)

        val deviceNameTextView = findViewById<TextView>(R.id.tv_pairing_device_name)
        val acceptButton = findViewById<Button>(R.id.btn_accept_pairing)
        val declineButton = findViewById<Button>(R.id.btn_decline_pairing)

        deviceNameTextView.text = getString(R.string.pairing_from_device, senderName)

        // The MITM-verification code is shown right here as brand-coloured boxes; the user compares it
        // against the sender's screen before tapping Accept (no separate confirm dialog needed).
        val verifyLabel = findViewById<TextView>(R.id.tv_pairing_verify_label)
        val pinBoxes = findViewById<View>(R.id.pin_code_container)
        if (!visualPin.isNullOrEmpty()) {
            PinBox.bind(this, visualPin)
            verifyLabel.visibility = View.VISIBLE
            pinBoxes.visibility = View.VISIBLE
        } else {
            verifyLabel.visibility = View.GONE
            pinBoxes.visibility = View.GONE
        }

        acceptButton.setOnClickListener {
            if (senderIp != null && aesKey != null) {
                completePairing(senderIp, aesKey, senderName, expiresAt)
            } else {
                clearNotification()
                finish()
            }
        }

        declineButton.setOnClickListener { declinePairing(senderIp, senderName) }
    }

    private fun completePairing(senderIp: String, aesKey: ByteArray, senderName: String, expiresAt: Long) {
        // finish() flips isFinishing synchronously, so this also blocks an Accept double-tap
        // from re-saving trust and re-broadcasting.
        if (isFinishing) return
        // The daemon's parked socket and the initiator's final read both give up after
        // PAIR_CONFIRM_WINDOW_MS. An Accept on a notification older than that would record
        // trust only on this side (AND-36) — tell the user to redo the pairing instead.
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            Toast.makeText(applicationContext, getString(R.string.pairing_request_expired), Toast.LENGTH_LONG).show()
            clearNotification()
            finish()
            return
        }
        SecurityVault.saveTrustedNode(applicationContext, senderIp, aesKey)
        SecurityVault.savePairedDeviceName(applicationContext, senderIp, senderName)
        sendBroadcast(Intent(PairingDaemon.ACTION_PAIRING_ACCEPTED).apply {
            putExtra("SENDER_IP", senderIp); setPackage(packageName)
        })
        Toast.makeText(applicationContext, getString(R.string.pairing_paired_with, senderName), Toast.LENGTH_SHORT).show()
        sendBroadcast(Intent("com.example.josephclipboardclient.PAIRING_SUCCESSFUL").setPackage(packageName))
        clearNotification()
        finish()
    }

    private fun declinePairing(senderIp: String?, senderName: String) {
        if (senderIp != null) {
            sendBroadcast(Intent(PairingDaemon.ACTION_PAIRING_DECLINED).apply {
                putExtra("SENDER_IP", senderIp); setPackage(packageName)
            })
        }
        Toast.makeText(applicationContext, getString(R.string.pairing_declined_simple), Toast.LENGTH_SHORT).show()
        clearNotification()
        finish()
    }

    private fun clearNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(65435)
        } catch (e: Exception) {} // best-effort notification dismiss
    }
}