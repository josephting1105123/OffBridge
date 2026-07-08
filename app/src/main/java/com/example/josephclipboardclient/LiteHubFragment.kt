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
                        onSendClick = { navigateTo(FileTransferFragment()) },
                        onReceiveClick = { navigateTo(ReceiveFragment()) },
                        onClipboardClick = { navigateTo(HistoryFragment()) }
                    )
                }
            }
        }
    }

    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
    }
}
