package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * How OffBridge opens a file it holds — shared by the history sheet's "Open" button and the
 * send-queue's tap-to-preview.
 *
 * The problem this solves: plenty of phones ship no `text/plain` viewer at all, so `ACTION_VIEW` on
 * a received `.txt` resolved to nothing and the user was left staring at a folder. We do have a
 * viewer that always works — our own — but forcing it would be worse than the bug: someone with a
 * proper editor installed wants that editor.
 *
 * So for text-like files we ask, with the guaranteed option preselected: **Preview** (built in,
 * cannot fail) is the dialog's default button, **Open with another app** is one tap away, and when
 * the device genuinely has no other handler there is nothing to choose and the preview opens
 * straight away.
 */
object FileOpenUi {

    /**
     * Characters shown by the built-in preview. It is a reader, not an editor: this keeps a huge
     * `.log` off the main thread's layout pass while still showing the head of the file, which the
     * old 1 MB "give up entirely" rule did not (the fallback was to reveal the folder, i.e. nothing).
     */
    private const val PREVIEW_CHAR_LIMIT = 128 * 1024

    /** True when some app *other than us* declares it can view [uri] as [mime]. Needs the
     *  ACTION_VIEW entry in the manifest's <queries> block to see past Android 11+ package
     *  visibility filtering. */
    fun hasExternalViewer(context: Context, uri: Uri, mime: String): Boolean = try {
        context.packageManager
            .queryIntentActivities(viewIntent(uri, mime), PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo?.packageName != context.packageName }
    } catch (_: Exception) {
        false
    }

    /**
     * Hands [uri] to another app, falling back from [mime] to the wildcard type (which surfaces the
     * file managers and editors that register for "any file"). Returns false when nothing accepted
     * it, so the caller can fall back.
     */
    fun openWithExternalApp(context: Context, uri: Uri, mime: String): Boolean {
        // Direct start, NOT createChooser: the system chooser always resolves, so it would never
        // throw even with zero targets and we would dead-end on an empty "no apps" screen. A plain
        // ACTION_VIEW throws ActivityNotFoundException when nothing matches, which is exactly the
        // signal we need. When several apps match, Android shows its own "Open with" list.
        if (startViewer(context, viewIntent(uri, mime))) return true
        return mime != FileOpenTypes.ANY_TYPE &&
            startViewer(context, viewIntent(uri, FileOpenTypes.ANY_TYPE))
    }

    /**
     * Built-in viewer: the head of the file in a scrollable, selectable dialog with a Copy action.
     * Returns false only when the bytes can't be read at all.
     */
    fun showTextPreview(context: Context, uri: Uri, name: String): Boolean {
        val (content, truncated) = readTextHead(context, uri) ?: return false
        val body = when {
            truncated -> content + "\n\n" + context.getString(R.string.preview_truncated)
            content.isEmpty() -> context.getString(R.string.preview_empty_file)
            else -> content
        }
        showSelectableTextDialog(
            context,
            title = name,
            text = body,
            copyButtonLabel = android.R.string.copy,
            copiedToast = R.string.history_toast_copied,
            // Copy the file's text, never the "…only the beginning is shown" footer.
            copyText = content,
        )
        return true
    }

    /**
     * Asks how to open a text-like [uri], with the built-in preview as the suggested default.
     * [onNothingWorked] runs only if every route failed (unreadable file *and* no app took it) —
     * the history sheet uses it to fall back to revealing the containing folder.
     */
    fun showTextOpenChoice(
        context: Context,
        uri: Uri,
        name: String,
        mime: String = FileOpenTypes.mimeForOpening(name),
        onNothingWorked: () -> Unit = {},
    ) {
        val preview = {
            if (!showTextPreview(context, uri, name) && !openWithExternalApp(context, uri, mime)) {
                onNothingWorked()
            }
        }
        // Nothing to choose between: skip the dialog rather than offering an option that dead-ends.
        if (!hasExternalViewer(context, uri, mime)) {
            preview()
            return
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(name)
            .setMessage(context.getString(R.string.open_choice_message))
            .setPositiveButton(R.string.open_choice_preview) { _, _ -> preview() }
            .setNeutralButton(R.string.open_choice_other_app) { _, _ ->
                if (!openWithExternalApp(context, uri, mime)) {
                    // The probe said an app existed but it refused the start (uninstalled since,
                    // disabled, a work-profile boundary) — don't strand the user.
                    Toast.makeText(context, R.string.open_choice_no_app, Toast.LENGTH_SHORT).show()
                    preview()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener { dialog.spaceButtons() }
        dialog.applyModalPolish()
        dialog.show()
    }

    private fun viewIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun startViewer(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Reads at most [PREVIEW_CHAR_LIMIT] characters of [uri] as UTF-8 (malformed bytes become the
     * replacement character rather than throwing), returning the text and whether more followed.
     * Null means the file could not be read at all. Allocation is bounded by the limit, not by the
     * file: previewing a 2 GB log must not OOM.
     */
    private fun readTextHead(context: Context, uri: Uri): Pair<String, Boolean>? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val reader = input.reader(Charsets.UTF_8)
            val buffer = CharArray(8 * 1024)
            val text = StringBuilder()
            var truncated = false
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                text.append(buffer, 0, read)
                if (text.length >= PREVIEW_CHAR_LIMIT) {
                    truncated = reader.read(buffer) != -1
                    break
                }
            }
            text.toString() to truncated
        }
    } catch (_: Exception) {
        null
    }
}
