package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * Themed modal bottom sheet — token-driven container, scrim and drag handle so screens stop
 * hand-rolling scrims/backgrounds. Top corners rounded to [Dimens.radiusXl].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = colors.bottomSheet,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = dimens.radiusXl, topEnd = dimens.radiusXl),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.divider) },
    ) {
        Column(Modifier.padding(dimens.spaceLg), content = content)
    }
}
