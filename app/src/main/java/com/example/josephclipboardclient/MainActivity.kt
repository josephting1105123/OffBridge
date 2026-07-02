package com.EntropicSoftwareLab.ClipboardShare

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.offgrid.NfcAntennaLocator
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavView: BottomNavigationView
    private val fragmentMap = mutableMapOf<Int, Fragment>()
    private var activeFragment: Fragment? = null

    // Back on a non-Send tab must not silently pop the hidden Send-to-Devices
    // screen off the back stack — background the app instead, like an empty stack.
    private val tabBackGuard = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            moveTaskToBack(false)
        }
    }

    // While the forced first-launch tour runs, back is swallowed — it can only end via Done.
    private val tourBackGuard = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { /* swallow */ }
    }

    companion object {
        var isAppInForeground = false
        var pendingClipboardPush = false
        /** ~2s worth of 50ms retries waiting for a tour tab's view to lay out before aborting. */
        private const val MAX_SEGMENT_WAIT_ATTEMPTS = 40
    }

    private val requestNotificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, getString(R.string.perm_notifications_required), Toast.LENGTH_LONG).show()
        }
    }

    private val requestStorageLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, getString(R.string.perm_storage_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)

        // Restore the user's chosen theme before inflating any layouts
        val savedTheme = prefs.getString("theme_mode", "System Default")
        val nightMode = when (savedTheme) {
            "Light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "Dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)

        setContentView(R.layout.activity_main)

        val rebootOverlay = findViewById<android.view.View>(R.id.rebootOverlay)
        if (intent.getBooleanExtra("is_rebooting", false)) {
            rebootOverlay.visibility = android.view.View.VISIBLE
            rebootOverlay.animate()
                .alpha(0f)
                .setStartDelay(500)
                .setDuration(400)
                .withEndAction { rebootOverlay.visibility = android.view.View.GONE }
                .start()
        }

        // Pre-warm the LayoutInflater cache for the tutorial tooltip so "How to use" is instant
        window.decorView.post {
            try {
                val inflater = android.view.LayoutInflater.from(this)
                inflater.inflate(R.layout.layout_tutorial_tooltip, null)
            } catch (e: Exception) {} // best-effort layout pre-warm; ignore inflation failures
        }

        val defaultDeviceName = getDefaultDeviceName()
        TransferDaemon.customDeviceName = prefs.getString("device_name", defaultDeviceName) ?: defaultDeviceName

        checkAndRequestPermissions()
        startClipboardEngine()

        bottomNavView = findViewById(R.id.bottom_nav_view)
        val fabMenu = findViewById<FloatingActionButton>(R.id.fab_menu)

        fragmentMap[R.id.nav_send_files] = FileTransferFragment()
        fragmentMap[R.id.nav_system_status] = SystemStatusFragment()
        fragmentMap[R.id.nav_history] = HistoryFragment()
        fragmentMap[R.id.nav_receive] = ReceiveFragment()

        val transaction = supportFragmentManager.beginTransaction()
        for ((_, fragment) in fragmentMap) {
            transaction.add(R.id.nav_host_fragment, fragment).hide(fragment)
        }
        transaction.commitNowAllowingStateLoss()

        if (savedInstanceState == null) {
            // THE FIX: Check the initial intent. If it's a share, handle it. Otherwise, load the default fragment.
            if (intent?.getBooleanExtra("SHOW_HISTORY", false) == true) {
                switchFragment(R.id.nav_send_files)
                bottomNavView.selectedItemId = R.id.nav_send_files

                val targetSessionId = intent.getStringExtra("HISTORY_SESSION_ID")
                showTransferHistoryDetail(targetSessionId)
                if (intent.getBooleanExtra("CLEAR_SEND_SELECTION", false)) {
                    (fragmentMap[R.id.nav_send_files] as? FileTransferFragment)?.clearSelectedFiles()
                }
                setIntent(Intent())
            } else if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
                handleShareIntent(intent)
            } else if (forcedTourPending()) {
                // First launch after onboarding: force the full 22-step guided tour.
                startForcedTour()
            } else {
                switchFragment(R.id.nav_send_files)
                bottomNavView.selectedItemId = R.id.nav_send_files
            }
        } else if (prefs.getBoolean(TutorialTour.KEY_IN_PROGRESS, false)
            && !prefs.getBoolean(TutorialTour.KEY_COMPLETED_V2, false)
            && !prefs.getBoolean(TutorialTour.KEY_COMPLETED, false)
        ) {
            // Recreated (e.g. rotation) mid-tour: the overlay isn't restored — restart from step 1.
            startForcedTour()
        }

        // Warm the NFC antenna-position cache once, off the critical path.
        window.decorView.post { NfcAntennaLocator.zoneFor(applicationContext) }

        bottomNavView.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_placeholder) return@setOnItemSelectedListener false
            if (activeFragment == fragmentMap[item.itemId]) return@setOnItemSelectedListener true
            switchFragment(item.itemId)
            true
        }

        bottomNavView.menu.findItem(R.id.nav_placeholder).isEnabled = false

        onBackPressedDispatcher.addCallback(this, tabBackGuard)
        // Added after tabBackGuard so it wins while enabled (last-added has highest priority).
        onBackPressedDispatcher.addCallback(this, tourBackGuard)
        supportFragmentManager.addOnBackStackChangedListener { updateTabBackGuard() }

        fabMenu.setOnClickListener {
            val menuBottomSheet = MenuBottomSheetFragment()
            menuBottomSheet.show(supportFragmentManager, menuBottomSheet.tag)
        }
    }

    // THE FIX: This is called when a share intent is received while the app is already running.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("SHOW_HISTORY", false) == true) {
            switchFragment(R.id.nav_send_files)
            bottomNavView.selectedItemId = R.id.nav_send_files
            val targetSessionId = intent.getStringExtra("HISTORY_SESSION_ID")
            showTransferHistoryDetail(targetSessionId)
            if (intent.getBooleanExtra("CLEAR_SEND_SELECTION", false)) {
                (fragmentMap[R.id.nav_send_files] as? FileTransferFragment)?.clearSelectedFiles()
            }
            setIntent(Intent())
        } else if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            handleShareIntent(intent)
        }
    }

    /**
     * Open the transfer-history detail for [sessionId]. If a sheet is already showing, reuse it (jump to
     * the new session) instead of stacking a second sheet — fixes "another one yet to close".
     */
    private fun showTransferHistoryDetail(sessionId: String?) {
        val existing = supportFragmentManager.findFragmentByTag(TransferHistoryBottomSheet.TAG) as? TransferHistoryBottomSheet
        if (existing != null && existing.isAdded) {
            existing.openSession(sessionId)
            return
        }
        TransferHistoryBottomSheet.newInstance(sessionId).show(supportFragmentManager, TransferHistoryBottomSheet.TAG)
    }

    // THE FIX: New centralized method to handle share intents safely.
    private fun handleShareIntent(intent: Intent?) {
        val uris = ArrayList<Uri>()
        if (intent?.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            }
        } else if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }

        if (uris.isNotEmpty()) {
            val bundle = Bundle().apply {
                putParcelableArrayList("uris", uris)
            }
            val fileTransferFragment = fragmentMap[R.id.nav_send_files] as FileTransferFragment
            fileTransferFragment.arguments = bundle
            switchFragment(R.id.nav_send_files)
            // Ensure the UI reflects that we are on the "Send Files" screen
            bottomNavView.selectedItemId = R.id.nav_send_files
        } else {
            // No file streams — a plain-text share ("Share → Clipboard Share" from any app). Push it
            // to paired devices through the clipboard pipeline (the reliable, permission-free
            // Android→peer text path) and surface it on the Clipboard History tab.
            val sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            if (!sharedText.isNullOrEmpty()) {
                startService(Intent(this, ClipboardService::class.java).apply {
                    action = ClipboardService.ACTION_EXECUTE_NETWORK_PUSH
                    putExtra("PAYLOAD", sharedText)
                })
                android.widget.Toast.makeText(this, getString(R.string.share_text_sending), android.widget.Toast.LENGTH_SHORT).show()
                switchFragment(R.id.nav_history)
                bottomNavView.selectedItemId = R.id.nav_history
            }
        }

        // Safely clear the intent so it's not processed again on rotation.
        setIntent(Intent())
    }


    private fun switchFragment(itemId: Int) {
        val targetFragment = fragmentMap[itemId] ?: return
        val transaction = supportFragmentManager.beginTransaction()

        activeFragment?.let { transaction.hide(it) }

        // The Send-to-Devices screen overlays the Send tab. It follows that tab:
        // hidden while another tab is open, re-shown on return — so the Send tab
        // stays on the devices screen instead of resetting to file selection.
        val sendOverlay = supportFragmentManager
            .findFragmentByTag(com.EntropicSoftwareLab.ClipboardShare.sendvia.SendToDevicesFragment.TAG)
        if (sendOverlay != null) {
            if (itemId == R.id.nav_send_files) {
                transaction.show(sendOverlay)
            } else {
                transaction.hide(sendOverlay)
            }
        }

        transaction.show(targetFragment)
        transaction.commitNowAllowingStateLoss()
        activeFragment = targetFragment
        updateTabBackGuard()
    }

    private fun updateTabBackGuard() {
        val overlayExists = supportFragmentManager
            .findFragmentByTag(com.EntropicSoftwareLab.ClipboardShare.sendvia.SendToDevicesFragment.TAG) != null
        tabBackGuard.isEnabled = overlayExists && activeFragment != fragmentMap[R.id.nav_send_files]
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        // The scanner's final step durably marks completion (survives process death).
        // On return, reconcile: if the tour is no longer pending, release the back guard.
        if (tourBackGuard.isEnabled && !forcedTourPending()) {
            tourBackGuard.isEnabled = false
            TutorialTour.finish()
        }
    }

    // ----------------------------------------------------- forced first-launch tour

    private fun forcedTourPending(): Boolean {
        val p = getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        // Grandfather users who finished the legacy v1 tour — don't re-force the new v2 tour on them.
        val done = p.getBoolean(TutorialTour.KEY_COMPLETED_V2, false) ||
                p.getBoolean(TutorialTour.KEY_COMPLETED, false)
        return !done || p.getBoolean(TutorialTour.KEY_IN_PROGRESS, false)
    }

    private fun startForcedTour() {
        getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE).edit()
            .putBoolean(TutorialTour.KEY_IN_PROGRESS, true).apply()
        TutorialTour.begin()               // before switchFragment, so per-tab auto-show stays suppressed
        tourBackGuard.isEnabled = true
        runTourStep(0)
    }

    /** Run [block] once the switched-to tab's view tree is laid out (correct spotlight coords).
     *  Bounded: if the view never appears (e.g. fragment creation failure), abort the tour
     *  rather than trap the user behind a no-skip overlay that never renders. */
    private fun startSegmentWhenLaidOut(itemId: Int, attempt: Int = 0, block: () -> Unit) {
        if (!TutorialTour.isForced()) return
        val v = fragmentMap[itemId]?.view
        if (v == null) {
            if (attempt >= MAX_SEGMENT_WAIT_ATTEMPTS) {
                abortForcedTour()
                return
            }
            bottomNavView.postDelayed({ startSegmentWhenLaidOut(itemId, attempt + 1, block) }, 50)
            return
        }
        v.doOnLayout { v.post { if (TutorialTour.isForced()) block() } }
    }

    /** Release the user if the tour can't proceed; mark complete so it won't re-loop next launch. */
    private fun abortForcedTour() {
        TutorialTour.markCompleted(this)
        tourBackGuard.isEnabled = false
    }

    /** One conceptual step per main interface in narrative order, then a how-to-use closer. */
    private data class TourSpec(val navId: Int, val textRes: Int)
    private val tourSteps: List<TourSpec> by lazy {
        listOf(
            TourSpec(R.id.nav_system_status, R.string.tour_pairing),
            TourSpec(R.id.nav_history, R.string.tour_clipboard),
            TourSpec(R.id.nav_send_files, R.string.tour_send_lan),
            TourSpec(R.id.nav_send_files, R.string.tour_send_qr),
            TourSpec(R.id.nav_receive, R.string.tour_receive),
            TourSpec(R.id.nav_send_files, R.string.tour_how_to_use),
        )
    }

    /** Switch to the step's tab, then spotlight that bottom-nav item with the conceptual tooltip. */
    private fun runTourStep(index: Int) {
        if (!TutorialTour.isForced()) return
        if (index >= tourSteps.size) { finishForcedTour(); return }
        val spec = tourSteps[index]
        switchFragment(spec.navId)
        bottomNavView.selectedItemId = spec.navId
        startSegmentWhenLaidOut(spec.navId) { showTourStepOverlay(index, spec) }
    }

    private fun showTourStepOverlay(index: Int, spec: TourSpec) {
        if (!TutorialTour.isForced()) return
        val itemView = bottomNavView.findViewById<android.view.View>(spec.navId)
        if (itemView == null) { runTourStep(index + 1); return }
        val root = window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
        val overlay = TutorialOverlayView(this)
        root.addView(
            overlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val isLast = index == tourSteps.size - 1
        val step = TutorialStep(
            targetView = itemView,
            text = getString(spec.textRes),
            hideSkipButton = true,
            // The closer spotlights the top-right chip, so let the tooltip auto-place (below it);
            // the nav-item steps keep the tooltip above the bottom bar.
            tooltipAboveSpotlight = !isLast,
            buttonLabel = if (isLast) null else getString(R.string.history_tutorial_next),
            // Steps 1-5: keep the hole inside the nav bar and off the neighbouring items.
            spotlightScale = if (index < 5) 0.95f else 1f,
            // Step 6: spotlight the Send screen's "How to use" chip (Compose — no View of its own),
            // falling back to the nav item if its bounds aren't available yet.
            targetBoundsProvider = if (isLast) {
                { (fragmentMap[R.id.nav_send_files] as? FileTransferFragment)?.howToUseChipBounds }
            } else null,
        )
        overlay.start(
            listOf(step),
            stepIndexOffset = index,
            totalStepsOverride = TutorialTour.TOUR_STEPS,
            onComplete = { if (isLast) finishForcedTour() else runTourStep(index + 1) },
        )
    }

    private fun finishForcedTour() {
        TutorialTour.markCompleted(this)
        tourBackGuard.isEnabled = false
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            syncClipboardOnFocus()
        }
    }

    private fun syncClipboardOnFocus() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val currentText = clip.getItemAt(0).text?.toString()
                if (!currentText.isNullOrEmpty()) {
                    val history = ClipboardHistoryManager.getHistory(this)
                    if (pendingClipboardPush || history.isEmpty() || history[0] != currentText) {
                        // Add to local history immediately
                        ClipboardHistoryManager.addEntry(this, currentText)
                        // Notify HistoryFragment to refresh
                        sendBroadcast(Intent(ClipboardService.ACTION_HISTORY_UPDATED).apply {
                            setPackage(packageName)
                        })
                        // Push to paired devices over network
                        val serviceIntent = Intent(this, ClipboardService::class.java).apply {
                            action = ClipboardService.ACTION_EXECUTE_NETWORK_PUSH
                            putExtra("PAYLOAD", currentText)
                        }
                        startService(serviceIntent)
                    }
                }
            }
        } catch (e: Exception) {
            // Silently ignore
        } finally {
            pendingClipboardPush = false
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:${packageName}")
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startClipboardEngine() {
        try {
            TransferDaemon.start(applicationContext)

            val serviceIntent = Intent(this, ClipboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getDefaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim() ?: ""
        val model = Build.MODEL?.trim() ?: ""

        // If model already starts with the manufacturer name, just capitalize model
        // e.g. "Samsung Galaxy S24" — don't make it "Samsung Samsung Galaxy S24"
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else if (manufacturer.isNotEmpty()) {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        } else {
            model.ifEmpty { getString(R.string.status_default_device_name) }
        }
    }
}