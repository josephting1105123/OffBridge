package com.EntropicSoftwareLab.ClipboardShare

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.LiteHubScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme

class LiteHubFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    LiteHubScreen(
                        onSendClick = { x, y -> navigateTo(FileTransferFragment(), x, y) },
                        onReceiveClick = { x, y -> navigateTo(ReceiveFragment(), x, y) },
                        onClipboardClick = { x, y -> navigateTo(HistoryFragment(), x, y) }
                    )
                }
            }
        }
    }

    private fun navigateTo(fragment: Fragment, x: Float, y: Float) {
        // The circular reveal ADDs (not replaces) the child on top of the hub, so a rapid double-tap
        // would otherwise stack two child copies. Bail if a child is already on the back stack or a
        // transaction is still pending.
        if (parentFragmentManager.backStackEntryCount > 0 || parentFragmentManager.isStateSaved) return
        fragment.arguments = Bundle().apply {
            putFloat("reveal_x", x)
            putFloat("reveal_y", y)
        }
        parentFragmentManager.beginTransaction()
            .add(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
        // Flush now so backStackEntryCount reflects this add before the next tap can be dispatched.
        parentFragmentManager.executePendingTransactions()
    }
}
