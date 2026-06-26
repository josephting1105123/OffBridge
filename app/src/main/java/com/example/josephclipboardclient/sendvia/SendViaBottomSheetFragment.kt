package com.EntropicSoftwareLab.ClipboardShare.sendvia

import android.app.Dialog
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Used only if the host bottom nav bar can't be measured (≈ a Material bottom nav's height). */
private val FALLBACK_NAV_BAR_HEIGHT = 64.dp

/**
 * "Send Via" chooser sheet (Compose). Delivers the chosen transport back via the Fragment Result API
 * so no callback is retained across rotation.
 */
class SendViaBottomSheetFragment : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY = "send_via_choice"
        const val RESULT_CHOICE = "choice"
        const val CHOICE_DEVICES = "devices"
        const val CHOICE_QR = "qr"
        const val CHOICE_NFC = "nfc"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // A present-but-disabled adapter stays enabled; the downstream NFC flow guides turning it on.
        val nfcUnavailable = NfcAdapter.getDefaultAdapter(requireContext()) == null
        // The host's bottom navigation bar height — used to lift the sheet above it so it isn't covered.
        val navBarHeightPx = activity?.findViewById<View?>(R.id.bottom_nav_view)?.height ?: 0
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    val bottomInset = if (navBarHeightPx > 0) {
                        with(LocalDensity.current) { navBarHeightPx.toDp() }
                    } else {
                        FALLBACK_NAV_BAR_HEIGHT
                    }
                    SendViaSheet(
                        nfcUnavailable = nfcUnavailable,
                        bottomInset = bottomInset,
                        onChoose = { choice ->
                            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_CHOICE to choice))
                            dismiss()
                        },
                    )
                }
            }
        }
    }
}
