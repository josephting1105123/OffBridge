package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.EntropicSoftwareLab.ClipboardShare.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows WHERE to tap for an NFC hand-off, auto-detecting this device's antenna position via
 * [NfcAntennaLocator]. In [ROLE_RECEIVE] it is also the FUNCTIONAL receive screen: it arms the NFC
 * reader while visible and, on a valid tap, hands the payload to the receive flow
 * ([OffGridSendActivity] / [MODE_RECEIVE]) and finishes. In [ROLE_SEND] it is a purely visual guide
 * (the sender's HCE payload + Wi-Fi Direct controller are armed by the send screen).
 */
class NfcTapGuideActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROLE = "ROLE"
        const val ROLE_SEND = "send"
        const val ROLE_RECEIVE = "receive"

        fun intent(ctx: Context, role: String): Intent =
            Intent(ctx, NfcTapGuideActivity::class.java).putExtra(EXTRA_ROLE, role)
    }

    private var receiveMode = false
    private val payloadFired = AtomicBoolean(false)

    private var tvReadStatus: TextView? = null
    private val nfcDiagLines = ArrayDeque<String>(6)

    // Binder-thread reader callback hopped onto the main thread before touching UI / navigation.
    // onEvent breadcrumbs ACCUMULATE (last 6) so a failed tap shows the full path to WHERE it stopped,
    // instead of the specific cause being overwritten by the final generic line.
    private val nfcReader = NfcPayloadReader(
        onRawPayload = { raw -> runOnUiThread { handleNfcPayload(raw) } },
        onEvent = { msg ->
            runOnUiThread {
                if (nfcDiagLines.size >= 6) nfcDiagLines.removeFirst()
                nfcDiagLines.addLast(msg)
                tvReadStatus?.text = nfcDiagLines.joinToString("\n")
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_tap_guide)

        receiveMode = intent.getStringExtra(EXTRA_ROLE) != ROLE_SEND

        val guide = findViewById<NfcTapGuideView>(R.id.nfcTapGuideView)
        guide.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        guide.antennaFraction = NfcAntennaLocator.antennaVerticalFraction(this)

        findViewById<TextView>(R.id.tvNfcGuideSubtitle).text =
            if (receiveMode) {
                getString(R.string.nfc_guide_subtitle_receive)
            } else {
                getString(R.string.nfc_guide_subtitle_send)
            }

        // Auto-detected, single static instruction based on where this phone's antenna sits.
        val hintRes = when (NfcAntennaLocator.zoneFor(this)) {
            NfcZone.TOP -> R.string.nfc_hint_auto_top
            NfcZone.CENTER -> R.string.nfc_hint_auto_center
            NfcZone.BOTTOM -> R.string.nfc_hint_auto_bottom
        }
        findViewById<TextView>(R.id.tvNfcGuideHint).setText(hintRes)

        // Live read-status line is only meaningful while actively listening (receive role).
        tvReadStatus = findViewById<TextView>(R.id.tvNfcReadStatus).apply {
            visibility = if (receiveMode) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnNfcGuideContinue).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (receiveMode) {
            payloadFired.set(false)
            nfcDiagLines.clear()
            val armed = NfcPayloadReader.arm(this, nfcReader)
            // Surface the arm result so a "nothing happens" tap isn't ambiguous: if reader mode could
            // not be enabled (NFC off / no hardware), say so instead of silently waiting forever.
            tvReadStatus?.text = getString(
                R.string.nfc_read_status_fmt,
                if (armed) getString(R.string.nfc_read_status_armed)
                else getString(R.string.nfc_read_status_no_nfc)
            )
        }
    }

    override fun onPause() {
        super.onPause()
        if (receiveMode) NfcPayloadReader.disarm(this)
    }

    private fun handleNfcPayload(raw: String) {
        if (!payloadFired.compareAndSet(false, true)) return
        when (OffGridPayload.parse(raw)) {
            is OffGridPayload.ParseResult.Valid -> {
                NfcPayloadReader.disarm(this)
                startActivity(Intent(this, OffGridSendActivity::class.java).apply {
                    putExtra(OffGridSendActivity.EXTRA_MODE, OffGridSendActivity.MODE_RECEIVE)
                    putExtra(OffGridSendActivity.EXTRA_PRESET_PAYLOAD, raw)
                })
                finish()
            }
            OffGridPayload.ParseResult.UnsupportedVersion -> {
                Toast.makeText(this, getString(R.string.offgrid_error_update_app), Toast.LENGTH_LONG).show()
                payloadFired.set(false)
            }
            OffGridPayload.ParseResult.Invalid -> {
                // Foreign / wrong-app tag — ignore and stay armed.
                payloadFired.set(false)
            }
        }
    }
}
