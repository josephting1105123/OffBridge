package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.UserAgreementScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme

class UserAgreementFragment : Fragment() {

    interface OnAgreementListener {
        fun onAgreementAccepted()
    }

    private var listener: OnAgreementListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnAgreementListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnAgreementListener")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    UserAgreementScreen(onAccept = { listener?.onAgreementAccepted() })
                }
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}