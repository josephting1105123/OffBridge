package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.EntropicSoftwareLab.ClipboardShare.R

/**
 * Host activity for the off-grid flow — a dedicated activity, matching the
 * existing convention for modal flows (PairingActivity, TransferRequestActivity).
 *
 * Sender mode ([MODE_SEND]): hosts [QrDisplayFragment] with the selected-file
 * extras from the "NFC / QR" chooser button.
 * Receiver mode ([MODE_RECEIVE]): hosts [QrScannerFragment], launched from the
 * power-options menu sheet.
 */
class OffGridSendActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "MODE"
        const val MODE_SEND = "send"
        const val MODE_RECEIVE = "receive"
        /** Send-screen presentation: show the QR code, or the NFC tap-position guide. */
        const val EXTRA_PRESENTATION = "PRESENTATION"
        const val PRESENTATION_QR = "qr"
        const val PRESENTATION_NFC = "nfc"
        /** Raw JSON payload pre-filled from an NFC tap on the Receive screen. */
        const val EXTRA_PRESET_PAYLOAD = "PRESET_PAYLOAD"
        /** Marks this as the tutorial-chained receive launch (renders step 3 / 3). */
        const val EXTRA_TUTORIAL = "TUTORIAL_MODE"
        /** Marks the scanner step as the final step (22/22) of the forced first-launch tour. */
        const val EXTRA_FORCED_TOUR = "FORCED_TOUR"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SEND
        // Sender renders as a slide-up sheet (rounded top, translucent dim over the previous screen);
        // the receiver stays full-screen (camera). Theme must be set before super.onCreate so the
        // translucent window + slide-up animation take effect.
        if (mode == MODE_SEND) setTheme(R.style.OffGridSlideUpTheme)
        super.onCreate(savedInstanceState)
        setContentView(
            if (mode == MODE_SEND) R.layout.activity_off_grid_send_sheet
            else R.layout.activity_off_grid_send
        )

        // During the forced tour's scanner step the user must not back out of it.
        if (intent.getBooleanExtra(EXTRA_FORCED_TOUR, false)) {
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* swallow: finish only via the tour's Done */ }
            })
        }

        if (savedInstanceState == null) {
            val fragment = if (mode == MODE_RECEIVE) {
                QrScannerFragment().apply {
                    arguments = Bundle().apply {
                        putString(
                            QrScannerFragment.ARG_PRESET_PAYLOAD,
                            intent.getStringExtra(EXTRA_PRESET_PAYLOAD)
                        )
                        putBoolean(
                            QrScannerFragment.ARG_TUTORIAL,
                            intent.getBooleanExtra(EXTRA_TUTORIAL, false)
                        )
                        putBoolean(
                            QrScannerFragment.ARG_FORCED_TOUR,
                            intent.getBooleanExtra(EXTRA_FORCED_TOUR, false)
                        )
                        // Forward presentation: PRESENTATION_NFC makes QrScannerFragment show the
                        // NFC tap-position guide instead of the camera (same reader + connect path).
                        putString(
                            QrScannerFragment.ARG_PRESENTATION,
                            intent.getStringExtra(EXTRA_PRESENTATION)
                        )
                    }
                }
            } else {
                QrDisplayFragment().apply { arguments = intent.extras }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.offGridFragmentContainer, fragment)
                .commit()
        }
    }
}
