package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.EntropicSoftwareLab.ClipboardShare.R
import androidx.appcompat.app.AppCompatDelegate

class OnboardingActivity : FragmentActivity(), UserAgreementFragment.OnAgreementListener, PermissionsFragment.OnPermissionsListener {

    private lateinit var viewPager: ViewPager2

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

        val hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding_v2", false)

        if (hasCompletedOnboarding) {
            launchMainActivity()
            return
        }

        setContentView(R.layout.activity_onboarding)

        // targetSdk 35+ forces edge-to-edge, so without this the page titles render under the
        // status bar / display cutout and the bottom buttons under the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.onboarding_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        viewPager = findViewById(R.id.onboarding_view_pager)
        val pagerAdapter = OnboardingPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        viewPager.isUserInputEnabled = false // Disable swiping between pages
    }

    override fun onResume() {
        super.onResume()
        // A ViewPager2 page fragment's onResume isn't reliably fired when returning from system
        // settings, so refresh the permissions page here — this is what auto-detects e.g. the Battery
        // exemption being set in Settings without needing an in-app button press.
        supportFragmentManager.fragments.filterIsInstance<PermissionsFragment>()
            .forEach { it.refreshPermissionStates() }
    }

    override fun onAgreementAccepted() {
        // This callback from the fragment tells us to move to the next page
        viewPager.currentItem = 1
    }

    override fun onOnboardingFinished() {
        // This callback from the fragment tells us the setup is complete
        val prefs = getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("has_completed_onboarding_v2", true).apply()
        launchMainActivity()
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Close this activity so the user can't go back to it
    }

    // This adapter provides the fragments (pages) to the ViewPager2
    private inner class OnboardingPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> UserAgreementFragment()
                1 -> PermissionsFragment()
                else -> throw IllegalStateException("Invalid position $position")
            }
        }
    }
}