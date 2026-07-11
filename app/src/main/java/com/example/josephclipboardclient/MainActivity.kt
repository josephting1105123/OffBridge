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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.EntropicSoftwareLab.ClipboardShare.offgrid.NfcAntennaLocator
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavView: BottomNavigationView
    private lateinit var fabMenu: FloatingActionButton
    private lateinit var liteNavBar: View
    private val fragmentMap = mutableMapOf<Int, Fragment>()
    private var activeFragment: Fragment? = null
    /** Tracks the live shell mode so the FAB/back-stack logic stays correct across an in-place switch. */
    private var currentIsLite = true
    /** Guards re-entrancy while the design-mode crossfade rebuild is running. */
    private var switchingMode = false

    // Back on a non-Send tab must not silently pop the hidden Send-to-Devices
    // screen off the back stack — background the app instead, like an empty stack.
    private val tabBackGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            moveTaskToBack(false)
        }
    }

    // While the forced first-launch tour runs, back is swallowed — it can only end via Done.
    private val tourBackGuard = object : OnBackPressedCallback(false) {
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

        val prefs = getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)

        // Restore the user's chosen theme before inflating any layouts
        val savedTheme = prefs.getString("theme_mode", "System Default")
        val nightMode = when (savedTheme) {
            "Light" -> AppCompatDelegate.MODE_NIGHT_NO
            "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        setContentView(R.layout.activity_main)

        val rebootOverlay = findViewById<View>(R.id.rebootOverlay)
        if (intent.getBooleanExtra("is_rebooting", false) || prefs.getBoolean("is_theme_rebooting", false)) {
            prefs.edit().remove("is_theme_rebooting").apply()
            rebootOverlay.visibility = View.VISIBLE
            rebootOverlay.animate()
                .alpha(0f)
                .setStartDelay(500)
                .setDuration(400)
                .withEndAction { rebootOverlay.visibility = View.GONE }
                .start()
        }

        // Pre-warm the LayoutInflater cache for the tutorial tooltip so "How to use" is instant
        window.decorView.post {
            try {
                val inflater = LayoutInflater.from(this)
                inflater.inflate(R.layout.layout_tutorial_tooltip, null)
            } catch (e: Exception) {} // best-effort layout pre-warm; ignore inflation failures
        }

        val defaultDeviceName = getDefaultDeviceName()
        TransferDaemon.customDeviceName = prefs.getString("device_name", defaultDeviceName) ?: defaultDeviceName

        checkAndRequestPermissions()
        startClipboardEngine()

        bottomNavView = findViewById(R.id.bottom_nav_view)
        fabMenu = findViewById(R.id.fab_menu)
        liteNavBar = findViewById(R.id.lite_nav_bar)
        // The Lite bar is a plain View with an EXPLICIT height, so it can never be measured full-height.
        // Height = max(the REAL Pro bar height measured/cached in Pro, a base + the nav-bar inset), so it
        // sits at exactly Pro's height once the user has seen Pro, and a safe fallback before then — this
        // is what stops it reading "too low". (Guarded so setting layoutParams doesn't loop the inset pass.)
        val liteBarBasePx = (72f * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(liteNavBar) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val cachedPro = prefs.getInt("pro_nav_bar_height", 0)
            val target = maxOf(cachedPro, liteBarBasePx + bottom)
            if (v.layoutParams.height != target) {
                v.layoutParams = v.layoutParams.apply { height = target }
            }
            insets
        }

        val isLite = isLiteMode(this)
        currentIsLite = isLite

        if (isLite) {
            // Lite never touches the Pro nav bar (mutating its menu/height at runtime was the source of
            // the "too low" / stretched-bar bugs). Hide it and use the dedicated lite_nav_bar; the power
            // FAB anchors to that bar and both are shown hub-only (see updateHubChrome).
            bottomNavView.visibility = View.GONE
            anchorFabTo(R.id.lite_nav_bar)
            ViewCompat.requestApplyInsets(liteNavBar)

            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, LiteHubFragment(), "main_tab_hub")
                    .commitNowAllowingStateLoss()
            }
        } else {
            bottomNavView.visibility = View.VISIBLE
            liteNavBar.visibility = View.GONE
            anchorFabTo(R.id.bottom_nav_view)
            cacheProNavBarHeight()
            fragmentMap[R.id.nav_send_files] = FileTransferFragment()
            fragmentMap[R.id.nav_system_status] = SystemStatusFragment()
            fragmentMap[R.id.nav_history] = HistoryFragment()
            fragmentMap[R.id.nav_receive] = ReceiveFragment()

            val transaction = supportFragmentManager.beginTransaction()
            for (id in fragmentMap.keys.toList()) {
                val tag = "main_tab_${resources.getResourceEntryName(id)}"
                val restored = supportFragmentManager.findFragmentByTag(tag)
                if (restored != null) {
                    fragmentMap[id] = restored
                } else {
                    val fragment = fragmentMap.getValue(id)
                    transaction.add(R.id.nav_host_fragment, fragment, tag).hide(fragment)
                }
            }
            transaction.commitNowAllowingStateLoss()
            if (savedInstanceState != null) {
                activeFragment = fragmentMap.values.firstOrNull { !it.isHidden }
            }
        }

        if (savedInstanceState == null) {
            // THE FIX: Check the initial intent. If it's a share, handle it. Otherwise, load the default fragment.
            if (intent?.getBooleanExtra("SHOW_HISTORY", false) == true) {
                if (isLite) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_fragment, HistoryFragment())
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                } else {
                    switchFragment(R.id.nav_send_files)
                    bottomNavView.selectedItemId = R.id.nav_send_files
                }

                val targetSessionId = intent.getStringExtra("HISTORY_SESSION_ID")
                showTransferHistoryDetail(targetSessionId)
                if (intent.getBooleanExtra("CLEAR_SEND_SELECTION", false)) {
                    if (isLite) {
                        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? FileTransferFragment)?.clearSelectedFiles()
                    } else {
                        (fragmentMap[R.id.nav_send_files] as? FileTransferFragment)?.clearSelectedFiles()
                    }
                }
                setIntent(Intent())
            } else if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
                handleShareIntent(intent)
            } else if (forcedTourPending()) {
                // First launch after onboarding: force the full 22-step guided tour.
                startForcedTour()
            } else {
                if (!isLite) {
                    switchFragment(R.id.nav_send_files)
                    bottomNavView.selectedItemId = R.id.nav_send_files
                }
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

        // The Pro nav-item listener is Pro-only (Lite hides the bar entirely).
        if (!isLite) installProNav()

        onBackPressedDispatcher.addCallback(this, tabBackGuard)
        // Added after tabBackGuard so it wins while enabled (last-added has highest priority).
        onBackPressedDispatcher.addCallback(this, tourBackGuard)
        // One mode-aware listener drives both the tab back-guard and the hub-only Lite chrome (bar +
        // FAB). Kept unconditional (not just in the Lite branch) so it stays correct after a mode switch.
        supportFragmentManager.addOnBackStackChangedListener {
            updateTabBackGuard()
            updateHubChrome()
        }
        updateHubChrome()

        setupMenuBottomSheet()
    }

    private fun setupMenuBottomSheet() {
        val bottomSheetView = findViewById<View>(R.id.bottom_sheet_menu_layout)
        val behavior = BottomSheetBehavior.from(bottomSheetView)
        val scrim = findViewById<View>(R.id.menu_scrim)
        
        // Hide initially
        behavior.state = BottomSheetBehavior.STATE_HIDDEN

        val menuBackGuard = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                behavior.state = BottomSheetBehavior.STATE_HIDDEN
            }
        }
        onBackPressedDispatcher.addCallback(this, menuBackGuard)
        
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    scrim.visibility = View.GONE
                    menuBackGuard.isEnabled = false
                } else {
                    // Cover the screen the moment the sheet leaves HIDDEN (settling/dragging/expanded),
                    // not only once onSlide first fires. A VISIBLE scrim intercepts every outside tap even
                    // while still transparent, so a tap can't slip through to the nav bar / FAB beneath
                    // before the fade begins — it's caught by the scrim, which closes the menu and consumes
                    // the event (misclick guard).
                    scrim.visibility = View.VISIBLE
                    menuBackGuard.isEnabled = true
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (slideOffset > 0) {
                    scrim.visibility = View.VISIBLE
                    scrim.alpha = slideOffset
                } else {
                    scrim.alpha = 0f
                }
            }
        })
        
        scrim.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                    behavior.state = BottomSheetBehavior.STATE_HIDDEN
                }
            }
            true // Consume all touch events to prevent fall-through to background UI
        }

        val btnSettings = bottomSheetView.findViewById<View>(R.id.btn_menu_settings)
        val btnWindows = bottomSheetView.findViewById<View>(R.id.btn_menu_windows)
        val toggleDesignMode = bottomSheetView.findViewById<ComposeView>(R.id.toggle_design_mode)
        val toggleThemeMode = bottomSheetView.findViewById<ComposeView>(R.id.toggle_theme_mode)
        val btnRestart = bottomSheetView.findViewById<View>(R.id.btn_menu_restart)
        val btnPowerOff = bottomSheetView.findViewById<View>(R.id.btn_menu_power_off)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        btnWindows.setOnClickListener {
            startActivity(Intent(this, WindowsVersionActivity::class.java))
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        toggleDesignMode.setContent {
            com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme {
                val prefsLocal = getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
                var isLiteSelected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(isLiteMode(this@MainActivity)) }
                val toggleColors = com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens.colors

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.EntropicSoftwareLab.ClipboardShare.ui.components.DesignModeToggle(
                        options = listOf(getString(R.string.design_mode_lite), getString(R.string.design_mode_pro)),
                        selectedIndex = if (isLiteSelected) 0 else 1,
                        // Lite = green, Pro = red. WCAG-safe token colours so the glass label stays readable.
                        selectedColors = listOf(toggleColors.greenText, toggleColors.error),
                        onSelect = { index ->
                            val toLite = index == 0
                            if (toLite != isLiteSelected) {
                                isLiteSelected = toLite
                                prefsLocal.edit().putString("design_mode", if (toLite) "Lite" else "Pro").apply()
                                // Let the thumb slide before the shell crossfade kicks in.
                                bottomSheetView.postDelayed({ requestDesignModeSwitch(toLite) }, 300)
                            }
                        }
                    )
                }
            }
        }

        // Theme toggle: Light / Dark two-state slider in the Lite/Pro glass language — no "System"
        // option in the UI. A fresh install still defaults to follow-system: theme_mode is unset, which
        // reads as "System Default" -> MODE_NIGHT_FOLLOW_SYSTEM in onCreate + ClipboardService. The
        // slider shows whichever side matches the current appearance; the instant the user picks a side
        // we persist "Light"/"Dark", which ends follow-system from then on. Keys stay "Light"/"Dark" so
        // every existing theme_mode reader keeps working.
        toggleThemeMode.setContent {
            com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme {
                val prefsLocal = getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
                val nowDark = (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                val toggleColors = com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens.colors
                // Dark/Moon side = purple. Like the amber/info tokens it shifts per app mode for
                // readability: violet-700 (#6D28D9) on a light UI, violet-400 (#A78BFA) on a dark UI.
                val moonPurple = if (nowDark) Color(0xFFA78BFA)
                                 else Color(0xFF6D28D9)
                var selectedTheme by androidx.compose.runtime.remember {
                    val saved = prefsLocal.getString("theme_mode", "System Default")
                    androidx.compose.runtime.mutableStateOf(
                        when (saved) {
                            "Light" -> 0
                            "Dark" -> 1
                            // Unset / "System Default": reflect the current system appearance WITHOUT
                            // persisting, so the app keeps following the system until the user picks.
                            else -> if (nowDark) 1 else 0
                        }
                    )
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.EntropicSoftwareLab.ClipboardShare.ui.components.DesignModeToggle(
                        options = listOf(
                            getString(R.string.theme_light),
                            getString(R.string.theme_dark)
                        ),
                        selectedIndex = selectedTheme,
                        // Light = amber (sun), Dark = purple (moon) — not green on both. The thumb
                        // crossfades amber<->purple as it slides; both stay readable on the glass.
                        selectedColors = listOf(toggleColors.warning, moonPurple),
                        onSelect = { index ->
                            // A follow-system user tapping the side that is ALREADY showing must
                            // still pin it: theme_mode is unset then, so index == selectedTheme,
                            // yet the tap is an explicit choice that ends follow-system. Without
                            // this the tap is a silent no-op and the app later flips with the OS
                            // against the user's pick. A same-side pin falls through harmlessly:
                            // targetDark == nowDark (no reboot overlay) and setDefaultNightMode
                            // with the current mode doesn't recreate.
                            val alreadyPinned = prefsLocal.getString("theme_mode", null)
                                .let { it == "Light" || it == "Dark" }
                            if (index != selectedTheme || !alreadyPinned) {
                                selectedTheme = index
                                val key = if (index == 0) "Light" else "Dark"
                                val targetDark = index == 1
                                // Only arm the reboot overlay when the resolved appearance will actually
                                // change — setDefaultNightMode won't recreate otherwise, and a stale flag
                                // paints a phantom black overlay on the next cold start.
                                val editor = prefsLocal.edit().putString("theme_mode", key)
                                if (targetDark != nowDark) editor.putBoolean("is_theme_rebooting", true)
                                editor.apply()

                                val nightMode = if (index == 0)
                                    AppCompatDelegate.MODE_NIGHT_NO
                                else
                                    AppCompatDelegate.MODE_NIGHT_YES
                                // Small delay so the thumb slide is visible before the activity recreate.
                                bottomSheetView.postDelayed({
                                    AppCompatDelegate.setDefaultNightMode(nightMode)
                                }, 300)
                            }
                        }
                    )
                }
            }
        }

        btnRestart.setOnClickListener {
            Toast.makeText(this, getString(R.string.menu_restart_toast), Toast.LENGTH_SHORT).show()
            try {
                val serviceIntent = Intent(this, ClipboardService::class.java)
                stopService(serviceIntent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { startForegroundService(serviceIntent) } else { startService(serviceIntent) }
            } catch (e: Exception) { e.printStackTrace() }

            val restartIntent = Intent(this, SplashActivity::class.java)
            restartIntent.putExtra("is_rebooting", true)
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(restartIntent)
            overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
        }

        btnPowerOff.setOnClickListener {
            Toast.makeText(this, getString(R.string.menu_power_off_toast), Toast.LENGTH_SHORT).show()
            try {
                TransferDaemon.stop()
                PairingDaemon.stop()
                stopService(Intent(this, ClipboardService::class.java))
            } catch (e: Exception) { e.printStackTrace() }
            finishAndRemoveTask()
        }

        fabMenu.setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    // THE FIX: This is called when a share intent is received while the app is already running.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("SHOW_HISTORY", false) == true) {
            // Use the LIVE shell mode, not the pref — during an in-place switch the pref leads the
            // shell by ~200ms, and targeting the wrong (not-yet-built) shell would mis-route/NPE.
            val isLite = currentIsLite
            if (isLite) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, HistoryFragment())
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            } else {
                switchFragment(R.id.nav_send_files)
                bottomNavView.selectedItemId = R.id.nav_send_files
            }
            val targetSessionId = intent.getStringExtra("HISTORY_SESSION_ID")
            showTransferHistoryDetail(targetSessionId)
            if (intent.getBooleanExtra("CLEAR_SEND_SELECTION", false)) {
                if (isLite) {
                    (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? FileTransferFragment)?.clearSelectedFiles()
                } else {
                    (fragmentMap[R.id.nav_send_files] as? FileTransferFragment)?.clearSelectedFiles()
                }
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
            // Live shell mode, not the pref: the Pro branch below casts fragmentMap[send] which is only
            // populated in the Pro shell, so a share arriving mid-switch (pref already flipped to Pro,
            // shell still Lite / map empty) must NOT take the Pro branch and NPE on the cast.
            val isLite = currentIsLite

            val fileTransferFragment = if (isLite) {
                val current = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                if (current is FileTransferFragment) current else FileTransferFragment().also {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_fragment, it)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
            } else {
                fragmentMap[R.id.nav_send_files] as FileTransferFragment
            }
            
            fileTransferFragment.ingestSharedUris(uris)
            
            if (!isLite) {
                switchFragment(R.id.nav_send_files)
                bottomNavView.selectedItemId = R.id.nav_send_files
            }
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
                Toast.makeText(this, getString(R.string.share_text_sending), Toast.LENGTH_SHORT).show()
                switchFragment(R.id.nav_history)
                bottomNavView.selectedItemId = R.id.nav_history
            }
        }

        // Safely clear the intent so it's not processed again on rotation.
        setIntent(Intent())
    }


    /**
     * Jumps to the Pairing (System Status) tab — used by the Send-to-Devices empty state.
     * Goes through the bottom nav so the selection listener runs the normal switch path
     * (which also hides the Send-to-Devices overlay until the user returns to the Send tab).
     */
    fun openPairingTab() {
        bottomNavView.selectedItemId = R.id.nav_system_status
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

    /** Pro: FAB always shown, no Lite bar. Lite: FAB + lite_nav_bar shown only on the hub (empty
     *  back stack), hidden on the Send / Receive / Clipboard child screens. */
    private fun updateHubChrome() {
        if (!::fabMenu.isInitialized || !::liteNavBar.isInitialized) return
        if (currentIsLite) {
            val onHub = supportFragmentManager.backStackEntryCount == 0
            val vis = if (onHub) View.VISIBLE else View.GONE
            fabMenu.visibility = vis
            liteNavBar.visibility = vis
        } else {
            fabMenu.visibility = View.VISIBLE
            liteNavBar.visibility = View.GONE
        }
    }

    /** Re-anchors the power FAB's top to [barId]'s top (+16dp), so it rides just above whichever bar
     *  the current mode shows (bottom_nav_view in Pro, lite_nav_bar in Lite). */
    private fun anchorFabTo(barId: Int) {
        val lp = fabMenu.layoutParams as ConstraintLayout.LayoutParams
        lp.topToTop = barId
        lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        lp.topMargin = (16f * resources.displayMetrics.density).toInt()
        fabMenu.layoutParams = lp
    }

    /** Wires the Pro bottom-nav tab selection (shared by onCreate and the live mode switch). */
    private fun installProNav() {
        bottomNavView.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_placeholder) return@setOnItemSelectedListener false
            if (activeFragment == fragmentMap[item.itemId]) return@setOnItemSelectedListener true
            switchFragment(item.itemId)
            true
        }
        bottomNavView.menu.findItem(R.id.nav_placeholder)?.isEnabled = false
    }

    /** Record the REAL Pro bottom-nav height once it's laid out, so the Lite bar can match it exactly
     *  (rather than guessing and reading "too low"). */
    private fun cacheProNavBarHeight() {
        bottomNavView.post {
            val h = bottomNavView.height
            if (h > 0) {
                getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt("pro_nav_bar_height", h).apply()
            }
        }
    }

    /**
     * Switches Lite⇄Pro in place — no activity restart — with a short crossfade. The power-menu sheet
     * is a separate window and stays open on top throughout (that's why the toggle can trigger this and
     * still be visible). Re-entrancy and no-op (same-mode) calls are ignored.
     */
    fun requestDesignModeSwitch(toLite: Boolean) {
        if (switchingMode || toLite == currentIsLite) return
        switchingMode = true
        val fadeViews = listOf(findViewById<View>(R.id.nav_host_fragment), bottomNavView, liteNavBar, fabMenu)
        fadeViews.forEach { it.animate().alpha(0f).setDuration(180L).start() }
        bottomNavView.postDelayed({
            // Bail if we were backgrounded / destroyed during the fade — the fragment transactions
            // below (popBackStackImmediate has no state-loss-safe variant) would otherwise throw after
            // onSaveInstanceState. The persisted pref still drives the correct shell on the next resume.
            if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) {
                // We faded out but won't rebuild — restore alpha so we don't leave a blank/dim shell.
                fadeViews.forEach { it.alpha = 1f }
                switchingMode = false
                return@postDelayed
            }
            rebuildShellFor(toLite)
            listOf(findViewById<View>(R.id.nav_host_fragment), bottomNavView, liteNavBar, fabMenu).forEach {
                it.alpha = 0f
                it.animate().alpha(1f).setDuration(200L).start()
            }
            switchingMode = false
            // Reconcile if the toggle was re-tapped during the switch (the pref moved again but that
            // second requestDesignModeSwitch was dropped by the switchingMode guard).
            val desiredLite = isLiteMode(this)
            if (desiredLite != currentIsLite) requestDesignModeSwitch(desiredLite)
        }, 200L)
    }

    /** Tears down the current shell (child back stack + fragments) and builds the other mode's shell.
     *  Only fragments in [R.id.nav_host_fragment] are removed — the power-menu dialog is left intact. */
    private fun rebuildShellFor(toLite: Boolean) {
        supportFragmentManager.popBackStackImmediate(
            null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val clear = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments
            .filter { it.id == R.id.nav_host_fragment }
            .forEach { clear.remove(it) }
        clear.commitNowAllowingStateLoss()
        fragmentMap.clear()
        activeFragment = null
        currentIsLite = toLite

        if (toLite) {
            // Hide the Pro nav bar (never mutate its menu) and switch the FAB to the Lite bar.
            bottomNavView.visibility = View.GONE
            anchorFabTo(R.id.lite_nav_bar)
            ViewCompat.requestApplyInsets(liteNavBar)
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, LiteHubFragment(), "main_tab_hub")
                .commitNowAllowingStateLoss()
        } else {
            liteNavBar.visibility = View.GONE
            bottomNavView.visibility = View.VISIBLE
            anchorFabTo(R.id.bottom_nav_view)
            cacheProNavBarHeight()
            installProNav()
            fragmentMap[R.id.nav_send_files] = FileTransferFragment()
            fragmentMap[R.id.nav_system_status] = SystemStatusFragment()
            fragmentMap[R.id.nav_history] = HistoryFragment()
            fragmentMap[R.id.nav_receive] = ReceiveFragment()
            val tx = supportFragmentManager.beginTransaction()
            for (id in fragmentMap.keys.toList()) {
                val tag = "main_tab_${resources.getResourceEntryName(id)}"
                val f = fragmentMap.getValue(id)
                tx.add(R.id.nav_host_fragment, f, tag).hide(f)
            }
            tx.commitNowAllowingStateLoss()
            switchFragment(R.id.nav_send_files)
            bottomNavView.selectedItemId = R.id.nav_send_files
        }
        updateHubChrome()
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        // Safety net: if a design-mode crossfade was interrupted by backgrounding (its rebuild bailed,
        // or a fade-in froze), the shell could be left dimmed/invisible. Restore full opacity on return.
        if (!switchingMode && ::liteNavBar.isInitialized) {
            listOf(findViewById<View>(R.id.nav_host_fragment), bottomNavView, liteNavBar, fabMenu)
                .forEach { it.alpha = 1f }
        }
        // The scanner's final step durably marks completion (survives process death).
        // On return, reconcile: if the tour is no longer pending, release the back guard.
        if (tourBackGuard.isEnabled && !forcedTourPending()) {
            tourBackGuard.isEnabled = false
            TutorialTour.finish()
        }
    }

    // ----------------------------------------------------- forced first-launch tour

    private fun forcedTourPending(): Boolean {
        val p = getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        if (isLiteMode(this)) return false
        // Grandfather users who finished the legacy v1 tour — don't re-force the new v2 tour on them.
        val done = p.getBoolean(TutorialTour.KEY_COMPLETED_V2, false) ||
                p.getBoolean(TutorialTour.KEY_COMPLETED, false)
        return !done || p.getBoolean(TutorialTour.KEY_IN_PROGRESS, false)
    }

    private fun startForcedTour() {
        getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE).edit()
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
        val itemView = bottomNavView.findViewById<View>(spec.navId)
        if (itemView == null) { runTourStep(index + 1); return }
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val overlay = TutorialOverlayView(this)
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
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