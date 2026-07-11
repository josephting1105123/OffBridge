package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.applyModalPolish
import com.EntropicSoftwareLab.ClipboardShare.spaceButtons
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue

/**
 * One-time entry of the user's MANUAL Wi-Fi hotspot SSID + password. Android gives apps no way to
 * read a manual hotspot's credentials, so the user supplies them here; they're stored encrypted via
 * [SecurityVault] and embedded in the off-grid QR so a receiver auto-joins the hotspot (the QR is
 * still mandatory — it also carries the transfer handshake). [onChanged] fires after a save/clear;
 * [onDismiss] fires on EVERY close (save / clear / cancel / back) — use it to re-sync a control (e.g.
 * a switch) to the actual saved-creds state regardless of how the dialog was closed.
 */
object HotspotCredentialsDialog {

    fun show(context: Context, onDismiss: () -> Unit = {}, onChanged: () -> Unit) {
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (8 * density).toInt()
        val gap = (12 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, 0)
        }

        val explain = TextView(context).apply {
            setText(R.string.settings_hotspot_dialog_msg)
            textSize = 13f
            val tv = TypedValue()
            context.theme.resolveAttribute(R.attr.appTextSecondaryColor, tv, true)
            setTextColor(tv.data)
            setPadding(0, 0, 0, gap)
        }

        val current = SecurityVault.getHotspotCredentials(context)

        val etSsid = EditText(context).apply {
            hint = context.getString(R.string.settings_hotspot_ssid_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(current?.first ?: "")
        }
        val etPsk = EditText(context).apply {
            hint = context.getString(R.string.settings_hotspot_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            // Force masked regardless of inputType/setSingleLine ordering quirks on some keyboards —
            // the password must be HIDDEN by default, toggled only via the (unchecked) Show checkbox.
            transformationMethod = PasswordTransformationMethod.getInstance()
            setText(current?.second ?: "")
        }
        val cbShow = CheckBox(context).apply {
            setText(R.string.settings_hotspot_show_password)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                etPsk.transformationMethod =
                    if (checked) null else PasswordTransformationMethod.getInstance()
                etPsk.setSelection(etPsk.text.length)
            }
        }

        // Reassure the user the hotspot is only used as a local link — getText (not getString)
        // keeps the <b> span so "no data will be consumed." renders bold.
        val dataNotice = TextView(context).apply {
            text = context.getText(R.string.settings_hotspot_data_notice)
            textSize = 13f
            val tv = TypedValue()
            context.theme.resolveAttribute(R.attr.appTextSecondaryColor, tv, true)
            setTextColor(tv.data)
            setPadding(0, gap, 0, 0)
        }

        // Android blocks apps from reading a manual hotspot's password, so guide the user to the
        // system Hotspot settings (Android 11+ shows the password + a share-QR there) to copy it.
        val linkOpenSettings = TextView(context).apply {
            setText(R.string.settings_hotspot_open_settings)
            textSize = 13f
            setTextColor(0xFF34D399.toInt())
            paint.isUnderlineText = true
            setPadding(0, gap, 0, 0)
            setOnClickListener { Net.openHotspotSettings(context) }
        }

        container.addView(explain)
        container.addView(etSsid)
        container.addView(etPsk, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap })
        container.addView(cbShow)
        container.addView(dataNotice)
        container.addView(linkOpenSettings)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_hotspot_title)
            .setView(container)
            .setPositiveButton(R.string.settings_hotspot_save, null) // overridden below to validate
            .setNeutralButton(R.string.settings_hotspot_clear) { d, _ ->
                SecurityVault.clearHotspotCredentials(context)
                onChanged()        // before dismiss() so onChanged precedes the onDismiss callback
                d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // Validate without auto-dismissing on invalid input.
        dialog.setOnShowListener {
            dialog.spaceButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = etSsid.text.toString().trim()
                val psk = etPsk.text.toString()
                when {
                    ssid.isEmpty() -> {
                        // Empty SSID = clear (turn the feature off).
                        SecurityVault.clearHotspotCredentials(context)
                        onChanged()
                        dialog.dismiss()
                    }
                    ssid.toByteArray(Charsets.UTF_8).size > 32 ->
                        Toast.makeText(context, R.string.settings_hotspot_toast_ssid_long, Toast.LENGTH_SHORT).show()
                    psk.length < 8 || psk.length > 63 ->
                        Toast.makeText(context, R.string.settings_hotspot_toast_psk_len, Toast.LENGTH_SHORT).show()
                    else -> {
                        SecurityVault.saveHotspotCredentials(context, ssid, psk)
                        onChanged()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.applyModalPolish()
        dialog.show()
    }
}
