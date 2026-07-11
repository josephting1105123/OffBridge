package com.EntropicSoftwareLab.ClipboardShare

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.EntropicSoftwareLab.ClipboardShare.R
import android.app.NotificationManager
import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatDelegate

class TransferRequestActivity : AppCompatActivity() {

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
        
        setContentView(R.layout.activity_file_receive)

        val sessionId = intent.getStringExtra("SESSION_ID")
        val senderName = intent.getStringExtra("SENDER_NAME") ?: "Unknown"
        val totalFiles = intent.getIntExtra("TOTAL_FILES", 0)

        if (sessionId == null) {
            finish()
            return
        }

        // Hide progress layer, show request layer
        findViewById<View>(R.id.receivingProgressLayer).visibility = View.GONE
        findViewById<View>(R.id.incomingRequestLayer).visibility = View.VISIBLE

        val tvIncomingDeviceName = findViewById<TextView>(R.id.tvIncomingDeviceName)
        val btnAccept = findViewById<Button>(R.id.btnAcceptTransfer)
        val btnDecline = findViewById<Button>(R.id.btnDeclineTransfer)

        tvIncomingDeviceName.text = getString(R.string.transfer_incoming_from, senderName, totalFiles)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(sessionId.hashCode())

        btnAccept.setOnClickListener {
            DownloadEngine.acceptOffer(this, sessionId)
            val uiIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("SHOW_HISTORY", true)
                putExtra("HISTORY_SESSION_ID", sessionId)
            }
            startActivity(uiIntent)
            finish()
        }

        btnDecline.setOnClickListener {
            DownloadEngine.declineOffer(this, sessionId)
            finish()
        }
    }
}
