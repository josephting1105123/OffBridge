package com.EntropicSoftwareLab.ClipboardShare

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone host for [DiagnosticsFragment], launched from the power-options menu.
 *
 * Surfaces the off-grid transport-ladder telemetry (which tier won / why each
 * failed) that is otherwise only written to Room + the JSONL log and never shown.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.diagnosticsFragmentContainer, DiagnosticsFragment())
                .commit()
        }
    }
}
