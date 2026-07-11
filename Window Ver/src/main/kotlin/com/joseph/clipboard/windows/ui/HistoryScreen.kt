package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.clickable
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.Screen
import com.joseph.clipboard.windows.theme.GreenPrimary
import com.joseph.clipboard.windows.theme.Dimens

import com.joseph.clipboard.windows.os.ClipboardManager
import androidx.compose.runtime.LaunchedEffect
import com.joseph.clipboard.windows.storage.TranslationManager

@Composable
fun HistoryScreen(
    appController: ClipboardAppController,
    onTutorialChainNext: (Screen) -> Unit = {},
    tutorialTrigger: Int = 0
) {
    val acceptClipboard by appController.acceptIncomingClipboard.collectAsState()
    val clipboardHistory by appController.clipboardHistory.collectAsState()
    val currentLanguage by TranslationManager.currentLanguageState

    var showTutorial by remember { mutableStateOf(false) }
    val tutorialBounds = remember { mutableStateMapOf<String, Rect>() }
    // Tapping a recent-copy card opens a full-text detail overlay (Android parity).
    var detailEntry by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tutorialTrigger) {
        if (tutorialTrigger > 0) showTutorial = true
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.spaceXl)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.spaceHuge, bottom = Dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier.padding(end = Dimens.spaceLg).size(Dimens.iconLg)
                )
                Text(
                    TranslationManager.getString("history_and_push_title").takeIf { it != "history_and_push_title" } ?: "Clipboard & Push",
                    style = MaterialTheme.typography.h5,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.weight(1f)
                )
                HowToUseChip(onClick = { showTutorial = true })
            }

            // Accept Clipboard Switch (moved to above the line)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.spaceLg)
                    .onGloballyPositioned { coords ->
                        tutorialBounds["acceptSwitch"] = coords.boundsInWindow()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    TranslationManager.getString("accept_incoming_clipboard").takeIf { it != "accept_incoming_clipboard" } ?: "Accept Incoming Clipboard",
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.weight(1f)
                )
                GlassSwitch(
                    checked = acceptClipboard,
                    onCheckedChange = { appController.updateAcceptIncomingClipboard(it) },
                )
            }

            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(bottom = Dimens.spaceLg))

            // Hint Text
            Text(
                TranslationManager.getString("recent_copies_hint").takeIf { it != "recent_copies_hint" } ?: "Tap any previous text to copy it again",
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = Dimens.spaceLg)
            )

            // History List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)
            ) {
                if (clipboardHistory.isEmpty()) {
                    item {
                        Text(
                            TranslationManager.getString("no_clipboard_history_yet").takeIf { it != "no_clipboard_history_yet" } ?: "No clipboard history yet.",
                            style = MaterialTheme.typography.body1,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    items(clipboardHistory.size) { index ->
                        val text = clipboardHistory[index]
                        Box(modifier = Modifier.onGloballyPositioned { coords ->
                            if (index == 0) tutorialBounds["historyList"] = coords.boundsInWindow()
                        }) {
                            HistoryItem(
                                text = text,
                                onOpen = { detailEntry = text },
                                onCopy = { ClipboardManager.writeText(text) },
                                onDelete = { appController.deleteFromHistory(text) }
                            )
                        }
                    }
                }
            }

            // Push Current Clipboard button — pinned at the bottom, below the recent-copies
            // list. The list scrolls inside its own weighted region above, so it never covers
            // this button and the button never moves as the list scrolls.
            Spacer(Modifier.height(Dimens.spaceLg))
            Box(modifier = Modifier.onGloballyPositioned { coords ->
                tutorialBounds["pushButton"] = coords.boundsInWindow()
            }) {
                AppButton(
                    text = TranslationManager.getString("action_push_current_clipboard").takeIf { it != "action_push_current_clipboard" } ?: "Push Current Clipboard",
                    onClick = { appController.pushCurrentClipboard() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

        }

        if (showTutorial) {
            TutorialOverlay(
                steps = listOf(
                    TutorialStep(
                        "pushButton",
                        "Tap here to instantly push your current clipboard text to all paired devices."
                    ),
                    TutorialStep(
                        "acceptSwitch",
                        "Turn this off if you don't want to receive clipboard content from other devices.",
                        hideSkipButton = true
                    )
                ),
                totalSteps = 2,
                stepOffset = 0,
                boundsMap = tutorialBounds,
                onDismiss = { showTutorial = false },
                onComplete = {
                    showTutorial = false
                    // No chain after this
                }
            )
        }

        // Full-text detail overlay — opened by tapping a card; mirrors Android's ClipboardDetail.
        detailEntry?.let { entry ->
            ClipboardDetailOverlay(
                text = entry,
                onBack = { detailEntry = null },
                onCopy = {
                    ClipboardManager.writeText(entry)
                    detailEntry = null
                }
            )
        }
    }
}

@Composable
fun HistoryItem(text: String, onOpen: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(Dimens.radiusLg))
            .clickable { onOpen() }
            .padding(Dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.take(100) + if (text.length > 100) "..." else "",
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = GreenPrimary)
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colors.error)
        }
    }
}

/**
 * Full-screen detail for one clipboard entry: back arrow + title, the complete scrollable text,
 * and a bottom Copy button. Drawn on top of the history list (Android parity with ClipboardDetail).
 */
@Composable
private fun ClipboardDetailOverlay(text: String, onBack: () -> Unit, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(Dimens.spaceXl)
    ) {
        // Header: back arrow + title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.spaceHuge, bottom = Dimens.spaceLg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = TranslationManager.getString("action_close").takeIf { it != "action_close" } ?: "Back",
                    tint = MaterialTheme.colors.onBackground
                )
            }
            Spacer(Modifier.width(Dimens.spaceSm))
            Text(
                TranslationManager.getString("copy_detail_title").takeIf { it != "copy_detail_title" } ?: "Copy Detail",
                style = MaterialTheme.typography.h5,
                color = MaterialTheme.colors.onBackground
            )
        }

        // Complete, scrollable text
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(Dimens.radiusLg))
                .verticalScroll(rememberScrollState())
                .padding(Dimens.spaceLg)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurface
            )
        }

        Spacer(Modifier.height(Dimens.spaceLg))

        // Copy button
        AppButton(
            text = TranslationManager.getString("copy_detail_button").takeIf { it != "copy_detail_button" } ?: "Copy",
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
