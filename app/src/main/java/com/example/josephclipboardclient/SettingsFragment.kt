package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SettingsScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab. The UI is Compose ([SettingsScreen], hosted via [ComposeView]); this fragment keeps
 * all logic — the SAF directory launcher, the theme/language/privacy AlertDialogs and the special
 * hotspot creds flow — and feeds the screen through observable state + callbacks.
 */
class SettingsFragment : Fragment() {

    private val languageLabelState = mutableStateOf("")
    private val saveLocationLabelState = mutableStateOf("")
    private val saveMediaLabelState = mutableStateOf("")
    private val hotspotAutoConnectState = mutableStateOf(false)
    private val acceptClipboardState = mutableStateOf(false)
    private val discoverableState = mutableStateOf(false)

    private val openDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val contentResolver = requireActivity().contentResolver
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)

            val prefs = requireActivity().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("save_location_uri", uri.toString()).apply()
            updateSaveLocationText(uri)

            // Re-pointing at a folder is the primary recovery trigger after a reinstall (the SAF
            // grant doesn't survive uninstall): pull any history backup that folder holds.
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    HistoryBackupManager.restoreFromSaveFolder(appCtx)
                }
                if (isAdded && !result.isEmpty) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.settings_history_recovered, result.clipboardAdded, result.transfersAdded),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    SettingsScreen(
                        languageLabel = languageLabelState.value,
                        saveLocationLabel = saveLocationLabelState.value,
                        saveMediaLabel = saveMediaLabelState.value,
                        hotspotAutoConnect = hotspotAutoConnectState.value,
                        acceptClipboard = acceptClipboardState.value,
                        discoverable = discoverableState.value,
                        onChangeLanguage = { showLanguageDialog() },
                        onChangeSaveLocation = { openDirectoryLauncher.launch(null) },
                        onChangeSaveMedia = { showSaveMediaDialog() },
                        onHotspotAutoConnectChange = ::onHotspotAutoConnectChange,
                        onAcceptClipboardChange = { enabled ->
                            ClipboardService.isSyncEnabled = enabled
                            acceptClipboardState.value = enabled
                        },
                        onDiscoverableChange = { enabled ->
                            SecurityVault.setDiscoverable(requireContext(), enabled)
                            discoverableState.value = enabled
                        },
                        onDonate = ::onDonate,
                        onPrivacyPolicy = { showPrivacyPolicyDialog() },
                        onConnectionDiagnosis = { startActivity(Intent(requireContext(), DiagnosticsActivity::class.java)) },
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Language pill label.
        updateLanguageButtonText()

        // Save-location label.
        val prefs = requireActivity().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        val savedUriString = prefs.getString("save_location_uri", null)
        if (savedUriString != null) {
            updateSaveLocationText(Uri.parse(savedUriString))
        } else {
            saveLocationLabelState.value = getString(R.string.settings_save_location_downloads)
        }

        // Save-media label (Gallery by default; see DownloadEngine routing).
        updateSaveMediaText()

        // Toggles.
        syncHotspotState()
        acceptClipboardState.value = ClipboardService.isSyncEnabled
        discoverableState.value = SecurityVault.isDiscoverable(requireContext())
    }

    private var hotspotSwitchUpdating = false

    /** Donate: open the Buy-Me-A-Coffee page; toast on failure (e.g. no browser installed). */
    private fun onDonate() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/josephting3")))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.settings_toast_could_not_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Hotspot auto-connect is "on" iff the user has saved their hotspot SSID/password (Android won't
     * let an app read a manual hotspot's creds). So the switch tracks creds-presence: toggling ON opens
     * the credentials dialog and the switch re-syncs to the real state when the dialog closes — staying
     * on if saved, reverting if cancelled; toggling OFF clears the saved creds.
     */
    private fun onHotspotAutoConnectChange(isChecked: Boolean) {
        if (hotspotSwitchUpdating) return
        if (isChecked) {
            if (SecurityVault.getHotspotCredentials(requireContext()) == null) {
                com.EntropicSoftwareLab.ClipboardShare.offgrid.HotspotCredentialsDialog.show(
                    requireContext(),
                    onDismiss = { if (isAdded) syncHotspotState() }
                ) { /* onChanged: the dismiss re-sync already reflects the saved state */ }
            }
            // else: creds already saved → auto-connect is already on; reflect that immediately.
            syncHotspotState()
        } else {
            SecurityVault.clearHotspotCredentials(requireContext())
            syncHotspotState()
        }
    }

    /** Reflect the saved-creds state on the Compose switch without re-triggering its change handler. */
    private fun syncHotspotState() {
        hotspotSwitchUpdating = true
        hotspotAutoConnectState.value = SecurityVault.getHotspotCredentials(requireContext()) != null
        hotspotSwitchUpdating = false
    }

    override fun onResume() {
        super.onResume()
        // Keep the duplicated clipboard / discoverable toggles in sync with their other homes.
        acceptClipboardState.value = ClipboardService.isSyncEnabled
        discoverableState.value = SecurityVault.isDiscoverable(requireContext())
        syncHotspotState()
    }

    // ==================== Language ====================

    private fun updateLanguageButtonText() {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else currentLocales.get(0)?.toLanguageTag() ?: ""
        val languages = arrayOf("System Default", "English", "简体中文", "繁體中文", "Español", "Français", "日本語", "Deutsch", "Português", "Bahasa Indonesia", "Русский")
        val localeTags = arrayOf("", "en-US", "zh-CN", "zh-TW", "es", "fr", "ja", "de", "pt", "id", "ru")
        var index = localeTags.indexOfFirst { it == currentTag }
        if (index == -1) index = localeTags.indexOfFirst { it.isNotEmpty() && currentTag.contains(it, ignoreCase = true) }
        if (index == -1) index = localeTags.indexOfFirst { currentTag.startsWith("in") && it == "id" } // fallback for legacy Indonesian
        if (index == -1) index = 0
        languageLabelState.value = languages[index]
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("System Default", "English", "简体中文", "繁體中文", "Español", "Français", "日本語", "Deutsch", "Português", "Bahasa Indonesia", "Русский")
        val localeTags = arrayOf("", "en-US", "zh-CN", "zh-TW", "es", "fr", "ja", "de", "pt", "id", "ru")
        
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else currentLocales.get(0)?.toLanguageTag() ?: ""
        var checkedItem = localeTags.indexOfFirst { it == currentTag }
        if (checkedItem == -1) checkedItem = localeTags.indexOfFirst { it.isNotEmpty() && currentTag.contains(it, ignoreCase = true) }
        if (checkedItem == -1) checkedItem = localeTags.indexOfFirst { currentTag.startsWith("in") && it == "id" } // fallback for legacy Indonesian
        if (checkedItem == -1) checkedItem = 0

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_dialog_choose_language))
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                val selectedTag = localeTags[which]
                val activity = requireActivity()
                val appCtx = activity.applicationContext
                val locales = if (selectedTag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList() 
                              else androidx.core.os.LocaleListCompat.forLanguageTags(selectedTag)
                AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
                
                android.widget.Toast.makeText(appCtx, appCtx.getString(R.string.settings_toast_rebooting_engine), android.widget.Toast.LENGTH_SHORT).show()
                try {
                    val serviceIntent = android.content.Intent(appCtx, ClipboardService::class.java)
                    appCtx.stopService(serviceIntent)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(serviceIntent)
                    } else {
                        appCtx.startService(serviceIntent)
                    }
                } catch (e: Exception) { e.printStackTrace() }

                val restartIntent = android.content.Intent(appCtx, SplashActivity::class.java)
                restartIntent.putExtra("is_rebooting", true)
                restartIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appCtx.startActivity(restartIntent)
                activity.overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()
            .also { it.applyModalPolish() }
            .show()
    }

    // ==================== Privacy Policy ====================

    private fun showPrivacyPolicyDialog() {
        val policyText = getString(R.string.privacy_policy_text)

        val scrollView = android.widget.ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = HtmlCompat.fromHtml(policyText, HtmlCompat.FROM_HTML_MODE_COMPACT)
            textSize = 14f
            setPadding(48, 32, 48, 32)
            val tv = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.appTextPrimaryColor, tv, true)
            setTextColor(tv.data)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_dialog_privacy_policy))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.settings_action_close), null)
            .create()
            .also { it.applyModalPolish() }
            .show()
    }

    // ==================== Save Location ====================

    private fun updateSaveLocationText(uri: Uri) {
        val selectedFolderLabel = getString(R.string.settings_save_location_selected_folder)
        // getTreeDocumentId throws IllegalArgumentException on a non-tree/malformed persisted URI,
        // which crashed the Settings tab every time it opened until prefs were cleared. Fall back to
        // the generic label instead of propagating the crash.
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            null
        }
        val folderName = docId?.substringAfterLast(':', selectedFolderLabel)?.ifEmpty { selectedFolderLabel }
        saveLocationLabelState.value = folderName ?: selectedFolderLabel
    }

    // ==================== Save Media ====================

    private fun updateSaveMediaText() {
        val prefs = requireActivity().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        val value = prefs.getString("save_media_to", TransferUtils.SAVE_MEDIA_GALLERY)
        saveMediaLabelState.value = if (value == TransferUtils.SAVE_MEDIA_SAME_AS_FILES) {
            getString(R.string.save_media_same_as_files)
        } else {
            getString(R.string.save_media_gallery)
        }
    }

    /** Two-choice picker for where received photos/videos land: the gallery collections, or the
     *  same folder as ordinary files. Mirrors the theme/language single-choice dialog pattern. */
    private fun showSaveMediaDialog() {
        val keys = arrayOf(TransferUtils.SAVE_MEDIA_GALLERY, TransferUtils.SAVE_MEDIA_SAME_AS_FILES)
        val names = arrayOf(getString(R.string.save_media_gallery), getString(R.string.save_media_same_as_files))
        val prefs = requireActivity().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        val current = prefs.getString("save_media_to", TransferUtils.SAVE_MEDIA_GALLERY)
        var checkedItem = keys.indexOf(current)
        if (checkedItem == -1) checkedItem = 0

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.save_media_to_setting))
            .setSingleChoiceItems(names, checkedItem) { dialog, which ->
                prefs.edit().putString("save_media_to", keys[which]).apply()
                updateSaveMediaText()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()
            .also { it.applyModalPolish() }
            .show()
    }
}