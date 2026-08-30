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
import android.os.Build
import android.util.TypedValue
import android.widget.ScrollView
import androidx.core.os.LocaleListCompat

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
    private val screenshotNotifyState = mutableStateOf(false)

    /**
     * Photo access for screenshot notifications.
     *
     * The toggle only sticks if the permission is actually granted: a switch that reads "on" while
     * the feature can never fire is exactly the silent failure this whole change is about.
     */
    private val requestScreenshotPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val ctx = context ?: return@registerForActivityResult
        if (granted) {
            ScreenshotWatcher.setEnabled(ctx, true)
        } else {
            Toast.makeText(ctx, getString(R.string.settings_screenshot_notify_denied), Toast.LENGTH_LONG).show()
        }
        screenshotNotifyState.value = ScreenshotWatcher.isActive(ctx)
    }

    private val openDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val contentResolver = requireActivity().contentResolver
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)

            val prefs = requireActivity().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
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
                        screenshotNotify = screenshotNotifyState.value,
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
                        onScreenshotNotifyChange = ::onScreenshotNotifyChange,
                        onDonate = ::onDonate,
                        onPrivacyPolicy = { showPrivacyPolicyDialog() },
                        onOpenSourceLicences = { showOssLicencesDialog() },
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
        val prefs = requireActivity().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
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
        // Reads the live permission too, so revoking photo access in system settings turns the
        // switch back off instead of leaving it claiming a feature that can no longer run.
        screenshotNotifyState.value = ScreenshotWatcher.isActive(requireContext())
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
    /**
     * Turns screenshot notifications on or off, asking for photo access the first time.
     *
     * The permission is requested here rather than at startup on purpose: it is the broadest thing
     * this app asks for, and asking for it only when someone switches on the one feature that needs
     * it is both easier to justify to the user and the honest scope.
     */
    private fun onScreenshotNotifyChange(enabled: Boolean) {
        val ctx = context ?: return
        if (!enabled) {
            ScreenshotWatcher.setEnabled(ctx, false)
            screenshotNotifyState.value = false
            return
        }
        if (ScreenshotWatcher.hasPermission(ctx)) {
            ScreenshotWatcher.setEnabled(ctx, true)
            screenshotNotifyState.value = ScreenshotWatcher.isActive(ctx)
        } else {
            requestScreenshotPermission.launch(ScreenshotWatcher.requiredPermission())
        }
    }

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
                val locales = if (selectedTag.isEmpty()) LocaleListCompat.getEmptyLocaleList() 
                              else LocaleListCompat.forLanguageTags(selectedTag)
                AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
                
                Toast.makeText(appCtx, appCtx.getString(R.string.settings_toast_rebooting_engine), Toast.LENGTH_SHORT).show()
                try {
                    val serviceIntent = Intent(appCtx, ClipboardService::class.java)
                    appCtx.stopService(serviceIntent)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(serviceIntent)
                    } else {
                        appCtx.startService(serviceIntent)
                    }
                } catch (e: Exception) { e.printStackTrace() }

                val restartIntent = Intent(appCtx, SplashActivity::class.java)
                restartIntent.putExtra("is_rebooting", true)
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appCtx.startActivity(restartIntent)
                activity.overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()
            .also { it.applyModalPolish() }
            .show()
    }

    // ==================== Privacy Policy ====================

    /**
     * The open-source licence texts, verbatim from res/raw/oss_licences.txt (built by
     * tools/build-oss-licences.mjs from LICENSE, NOTICE and licenses/). Deliberately not
     * translated and deliberately not run through HtmlCompat: these are legal texts, and the
     * Apache and BSD bodies contain bare angle brackets that an HTML parser would eat.
     *
     * ~26KB read straight off the APK's resource table, which is memory-mapped — cheap enough
     * that keeping it on the main thread beats the complexity of a background load here.
     */
    private fun showOssLicencesDialog() {
        val licences = try {
            resources.openRawResource(R.raw.oss_licences).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // Never let a missing/corrupt resource crash Settings; the row is informational.
            Toast.makeText(requireContext(), getString(R.string.settings_toast_could_not_open_browser), Toast.LENGTH_SHORT).show()
            return
        }

        val scrollView = ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = licences
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(false)
            setPadding(40, 32, 40, 32)
            setTextIsSelectable(true)
            val tv = TypedValue()
            requireContext().theme.resolveAttribute(R.attr.appTextPrimaryColor, tv, true)
            setTextColor(tv.data)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_oss_licences))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.settings_action_close), null)
            .create()
            .also { it.applyModalPolish() }
            .show()
    }

    private fun showPrivacyPolicyDialog() {
        val policyText = getString(R.string.privacy_policy_text)

        val scrollView = ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = HtmlCompat.fromHtml(policyText, HtmlCompat.FROM_HTML_MODE_COMPACT)
            textSize = 14f
            setPadding(48, 32, 48, 32)
            val tv = TypedValue()
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
        val prefs = requireActivity().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
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
        val prefs = requireActivity().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
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