package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.os.FileDialogHelper
import com.joseph.clipboard.windows.theme.GreenPrimary
import com.joseph.clipboard.windows.theme.ThemeMode
import com.joseph.clipboard.windows.theme.Dimens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.awt.Desktop
import java.net.URI
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogWindow
import com.joseph.clipboard.windows.LocalThemeMode
import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.LocalExtraColors

@Composable
fun SettingsScreen(appController: ClipboardAppController) {
    val themeModeState = LocalThemeMode.current
    var saveLocation by remember { mutableStateOf(SecurityVault.getSaveLocation()) }
    var autoUpdateCheckEnabled by remember { mutableStateOf(SecurityVault.isAutoUpdateCheckEnabled()) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    val currentLanguage by TranslationManager.currentLanguageState
    val extra = LocalExtraColors.current
    val scope = rememberCoroutineScope()

    // Uniform width for every settings value button: the "System Default" value text + ~2 chars of
    // breathing room + the button's horizontal padding, so all rows line up at one length.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val buttonStyle = MaterialTheme.typography.button
    val uniformButtonWidth = remember(buttonStyle) {
        val base = measurer.measure(AnnotatedString("System Default"), buttonStyle).size.width
        val two = measurer.measure(AnnotatedString("MM"), buttonStyle).size.width
        with(density) { (base + two).toDp() } + Dimens.spaceLg * 2
    }

    // DEVICE-section toggles (device-name editing now lives only in the Pairing tab).
    val isDiscoverable by appController.isDiscoverable.collectAsState()
    val acceptClipboard by appController.acceptIncomingClipboard.collectAsState()

    val generalText = TranslationManager.getString("settings_section_general")
    val deviceText = TranslationManager.getString("settings_section_device").takeIf { it != "settings_section_device" } ?: "DEVICE"
    val discoverableLabel = TranslationManager.getString("discoverable_to_other_devices").takeIf { it != "discoverable_to_other_devices" } ?: "Discoverable to other devices"
    val acceptClipboardLabel = TranslationManager.getString("accept_incoming_clipboard").takeIf { it != "accept_incoming_clipboard" } ?: "Accept Incoming Clipboard"
    val historyBackupNote = TranslationManager.getString("settings_history_backup_note").takeIf { it != "settings_history_backup_note" } ?: "Clipboard and transfer history is backed up to your save-location folder."
    val themeText = TranslationManager.getString("theme_setting")
    val languageText = TranslationManager.getString("language_setting")
    val saveToText = TranslationManager.getString("save_to_setting")
    val updatesText = TranslationManager.getString("settings_section_updates").takeIf { it != "settings_section_updates" } ?: "Updates"
    val autoUpdateCheckLabel = TranslationManager.getString("update_auto_check_label").takeIf { it != "update_auto_check_label" } ?: "Automatically check for updates"
    val othersText = TranslationManager.getString("settings_section_others").takeIf { it != "settings_section_others" } ?: "Others"
    val supportText = TranslationManager.getString("support_setting").takeIf { it != "support_setting" } ?: "Support"
    val privacyText = TranslationManager.getString("privacy_policy_setting").takeIf { it != "privacy_policy_setting" } ?: "Privacy Policy"
    val donateText = TranslationManager.getString("action_donate").takeIf { it != "action_donate" } ?: "☕ Donate"
    val readPolicyText = TranslationManager.getString("action_read_privacy_policy").takeIf { it != "action_read_privacy_policy" } ?: "📄 Read"

    val chooseThemeText = TranslationManager.getString("dialog_title_choose_theme")
    val chooseLanguageText = TranslationManager.getString("dialog_title_choose_language")
    val privacyPolicyTitleText = TranslationManager.getString("dialog_title_privacy_policy")
    val closeText = TranslationManager.getString("action_close_with_check").replace("o ", "").replace("Close", "Close") 
    val cancelText = TranslationManager.getString("action_cancel")
    
    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier.padding(end = Dimens.spaceLg).size(Dimens.iconLg)
                )
                Text(
                    TranslationManager.getString("settings_title").takeIf { it != "settings_title" } ?: "Settings",
                    style = MaterialTheme.typography.h5,
                    color = MaterialTheme.colors.onBackground
                )
            }

            // GENERAL
            SectionHeader(generalText)
            GlassCard {
                val currentModeName = when (themeModeState.value) {
                    ThemeMode.SYSTEM -> TranslationManager.getString("theme_system_default").takeIf { it != "theme_system_default" } ?: "System Default"
                    ThemeMode.LIGHT -> TranslationManager.getString("theme_light").takeIf { it != "theme_light" } ?: "Light"
                    ThemeMode.DARK -> TranslationManager.getString("theme_dark").takeIf { it != "theme_dark" } ?: "Dark"
                }
                SettingsRow(themeText, currentModeName, uniformButtonWidth) { showThemeDialog = true }
                SettingsRow(languageText, currentLanguage, uniformButtonWidth) { showLanguageDialog = true }
                SettingsRow(
                    label = saveToText,
                    value = saveLocation.substringAfterLast("\\").ifEmpty { saveLocation },
                    width = uniformButtonWidth,
                    onClick = {
                        // Blocking PowerShell picker — keep it off the Compose UI thread
                        // (mirrors the FileTransferScreen call sites).
                        scope.launch(Dispatchers.IO) {
                            val dir = FileDialogHelper.openDirectoryPicker()
                            if (dir != null) {
                                saveLocation = dir.absolutePath
                                SecurityVault.setSaveLocation(dir.absolutePath)
                            }
                        }
                    }
                )
                Spacer(Modifier.height(Dimens.spaceSm))
                Text(historyBackupNote, style = MaterialTheme.typography.caption, color = extra.textSecondary)
            }
            Spacer(Modifier.height(Dimens.spaceXl))

            // DEVICE
            SectionHeader(deviceText)
            GlassCard {
                GlassToggleRow(discoverableLabel, isDiscoverable, onCheckedChange = { appController.updateDiscoverable(it) })
                GlassToggleRow(acceptClipboardLabel, acceptClipboard, onCheckedChange = { appController.updateAcceptIncomingClipboard(it) })
            }
            Spacer(Modifier.height(Dimens.spaceXl))

            // UPDATES — gates only the passive startup check/badge; opening the Updates page
            // always checks fresh regardless of this toggle, since that's an explicit user action.
            SectionHeader(updatesText)
            GlassCard {
                GlassToggleRow(
                    autoUpdateCheckLabel,
                    autoUpdateCheckEnabled,
                    onCheckedChange = {
                        autoUpdateCheckEnabled = it
                        SecurityVault.setAutoUpdateCheckEnabled(it)
                    }
                )
            }
            Spacer(Modifier.height(Dimens.spaceXl))

            // OTHERS
            SectionHeader(othersText)
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(supportText, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface, modifier = Modifier.weight(1f))
                    SettingValueButton(
                        text = donateText,
                        width = uniformButtonWidth,
                        textColor = extra.greenText,
                        onClick = {
                            try {
                                // guard browse support before calling, matching other call sites
                                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                    Desktop.getDesktop().browse(URI("https://buymeacoffee.com/josephting3"))
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                }
                SettingsRow(privacyText, readPolicyText, uniformButtonWidth) { showPrivacyDialog = true }
            }
        }
        
        if (showThemeDialog) {
            BackdropEffect() // blur the main window's content while this OS dialog is up
            DialogWindow(
                onCloseRequest = { showThemeDialog = false },
                title = chooseThemeText,
                resizable = false,
                state = androidx.compose.ui.window.rememberDialogState(
                    width = 350.dp, height = 300.dp
                )
            ) {
                MaterialTheme(colors = if (themeModeState.value == ThemeMode.LIGHT) lightColors() else darkColors()) {
                    Surface(color = MaterialTheme.colors.background) {
                        val state = rememberScrollState()
                        Box(modifier = Modifier.fillMaxSize().padding(Dimens.spaceLg)) {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(state)) {
                                ThemeMode.values().forEach { mode ->
                                    val modeName = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            themeModeState.value = mode
                                            showThemeDialog = false
                                        }.padding(Dimens.spaceMd),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = themeModeState.value == mode,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = GreenPrimary)
                                        )
                                        Spacer(modifier = Modifier.width(Dimens.spaceLg))
                                        val translatedModeName = when (mode) {
                                            ThemeMode.SYSTEM -> TranslationManager.getString("theme_system_default").takeIf { it != "theme_system_default" } ?: "System Default"
                                            ThemeMode.LIGHT -> TranslationManager.getString("theme_light").takeIf { it != "theme_light" } ?: "Light"
                                            ThemeMode.DARK -> TranslationManager.getString("theme_dark").takeIf { it != "theme_dark" } ?: "Dark"
                                        }
                                        Text(translatedModeName, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface)
                                    }
                                }
                            }
                            VerticalScrollbar(
                                adapter = androidx.compose.foundation.rememberScrollbarAdapter(state),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
        
        if (showLanguageDialog) {
            BackdropEffect()
            DialogWindow(
                onCloseRequest = { showLanguageDialog = false },
                title = chooseLanguageText,
                resizable = false,
                state = androidx.compose.ui.window.rememberDialogState(
                    width = 350.dp, height = 450.dp
                )
            ) {
                MaterialTheme(colors = if (themeModeState.value == ThemeMode.LIGHT) lightColors() else darkColors()) {
                    Surface(color = MaterialTheme.colors.background) {
                        val languages = listOf("System Default", "English", "简体中文", "繁體中文", "Español", "Français", "日本語", "Deutsch", "Português", "Bahasa Indonesia", "Русский")
                        val state = rememberScrollState()
                        Box(modifier = Modifier.fillMaxSize().padding(Dimens.spaceLg)) {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(state)) {
                                languages.forEach { lang ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            TranslationManager.setLanguage(lang)
                                            showLanguageDialog = false
                                        }.padding(Dimens.spaceMd),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = currentLanguage == lang,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = GreenPrimary)
                                        )
                                        Spacer(modifier = Modifier.width(Dimens.spaceLg))
                                        Text(lang, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface)
                                    }
                                }
                            }
                            VerticalScrollbar(
                                adapter = androidx.compose.foundation.rememberScrollbarAdapter(state),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
        
        if (showPrivacyDialog) {
            BackdropEffect()
            DialogWindow(
                onCloseRequest = { showPrivacyDialog = false },
                title = privacyPolicyTitleText,
                resizable = false,
                state = androidx.compose.ui.window.rememberDialogState(
                    width = 550.dp, height = 500.dp
                )
            ) {
                MaterialTheme(colors = if (themeModeState.value == ThemeMode.LIGHT) lightColors() else darkColors()) {
                    Surface(color = MaterialTheme.colors.background) {
                        val policyText = TranslationManager.getPrivacyPolicy()
                        val paragraphs = policyText.split("\n")
                        val state = rememberScrollState()
                        Box(modifier = Modifier.fillMaxSize().padding(Dimens.spaceLg)) {
                            Column(modifier = Modifier.fillMaxSize().padding(end = Dimens.spaceMd).verticalScroll(state)) {
                                paragraphs.forEach { para ->
                                    if (para.isNotBlank()) {
                                        Text(para, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface, modifier = Modifier.padding(bottom = Dimens.spaceSm))
                                    }
                                }
                                Spacer(modifier = Modifier.height(Dimens.spaceLg))
                            }
                            VerticalScrollbar(
                                adapter = androidx.compose.foundation.rememberScrollbarAdapter(state),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(bottom = Dimens.spaceLg)
    )
}

@Composable
fun SettingsRow(label: String, value: String, width: Dp, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface, modifier = Modifier.weight(1f))
        SettingValueButton(value, width, onClick)
    }
}

/** A fixed-width, rounded-rectangle value button so every settings row lines up at one length. */
@Composable
private fun SettingValueButton(
    text: String,
    width: Dp,
    onClick: () -> Unit,
    textColor: Color? = null,
) {
    val extra = LocalExtraColors.current
    Box(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(extra.surfaceElevated)
            .border(Dimens.hairlineWidth, extra.divider, RoundedCornerShape(Dimens.radiusMd))
            .clickable { onClick() }
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.button,
            color = textColor ?: MaterialTheme.colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
