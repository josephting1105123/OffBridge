package com.EntropicSoftwareLab.ClipboardShare

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.EntropicSoftwareLab.ClipboardShare.R

class TransferRequestActivity : AppCompatActivity() {

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
        
        setContentView(R.layout.activity_file_receive)

        val sessionId = intent.getStringExtra("SESSION_ID")
        val senderName = intent.getStringExtra("SENDER_NAME") ?: "Unknown"
        val totalFiles = intent.getIntExtra("TOTAL_FILES", 0)

        if (sessionId == null) {
            finish()
            return
        }

        // Hide progress layer, show request layer
        findViewById<android.view.View>(R.id.receivingProgressLayer).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.incomingRequestLayer).visibility = android.view.View.VISIBLE

        val tvIncomingDeviceName = findViewById<TextView>(R.id.tvIncomingDeviceName)
        val btnAccept = findViewById<Button>(R.id.btnAcceptTransfer)
        val btnDecline = findViewById<Button>(R.id.btnDeclineTransfer)

        tvIncomingDeviceName.text = getString(R.string.transfer_incoming_from, senderName, totalFiles)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(sessionId.hashCode())

        btnAccept.setOnClickListener {
            TransferDaemon.acceptOffer(this, sessionId)
            val uiIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("SHOW_HISTORY", true)
                putExtra("HISTORY_SESSION_ID", sessionId)
            }
            startActivity(uiIntent)
            finish()
        }

        btnDecline.setOnClickListener {
            TransferDaemon.declineOffer(this, sessionId)
            finish()
        }
    }
}
