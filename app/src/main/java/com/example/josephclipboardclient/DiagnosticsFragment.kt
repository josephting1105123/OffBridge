package com.EntropicSoftwareLab.ClipboardShare

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository
import com.EntropicSoftwareLab.ClipboardShare.transport.AttemptOutcome
import com.EntropicSoftwareLab.ClipboardShare.transport.FailureCode
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportAttemptLog
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Read-only view of the last off-grid transport-ladder run: which tier won, or
 * the per-tier outcome + failure code for each rung. The data already exists in
 * the `transport_attempt` Room table and `files/transport_attempts.jsonl`; this
 * screen is the only place it surfaces. A Share button exports the raw JSONL.
 */
class DiagnosticsFragment : Fragment() {

    private lateinit var sessionHeader: TextView
    private lateinit var emptyView: TextView
    private lateinit var attemptsContainer: LinearLayout
    private lateinit var traceHeader: TextView
    private lateinit var traceView: TextView
    private lateinit var shareBtn: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_diagnostics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionHeader = view.findViewById(R.id.diagSessionHeader)
        emptyView = view.findViewById(R.id.diagEmpty)
        attemptsContainer = view.findViewById(R.id.diagAttemptsContainer)
        traceHeader = view.findViewById(R.id.diagTraceHeader)
        traceView = view.findViewById(R.id.diagTrace)
        shareBtn = view.findViewById(R.id.btn_diag_share)

        val appContext = requireContext().applicationContext
        // Use viewLifecycleOwner.lifecycleScope so a fresh scope is created each
        // time the view is (re)created. A field-level scope cancelled in
        // onDestroyView would stay cancelled after a back-stack pop, making this
        // launch silently no-op and leaving the screen empty.
        viewLifecycleOwner.lifecycleScope.launch {
            val (diag, trace) = withContext(Dispatchers.IO) {
                OffGridRepository.lastSessionDiagnostics(appContext) to
                    TransportDiag.persistedTrace(appContext)
            }
            if (!isAdded) return@launch
            render(diag, trace)
        }
    }

    private fun render(
        diag: OffGridRepository.Diagnostics?,
        trace: TransportDiag.PersistedTrace?
    ) {
        attemptsContainer.removeAllViews()
        if (diag == null && trace == null) {
            emptyView.visibility = View.VISIBLE
            sessionHeader.visibility = View.GONE
            traceHeader.visibility = View.GONE
            traceView.visibility = View.GONE
            shareBtn.visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE

        if (diag != null) {
            val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(diag.startedUtc))
            val winnerLine = diag.winningTierName
                ?.let { getString(R.string.diagnostics_connected_via, it) }
                ?: getString(R.string.diagnostics_all_failed)
            sessionHeader.text = "$time · ${diag.attempts.size} method(s)\n$winnerLine\n${diag.sessionId}"
            sessionHeader.visibility = View.VISIBLE
            for (row in diag.attempts) {
                attemptsContainer.addView(rowView(rowText(row)))
            }
        } else {
            sessionHeader.visibility = View.GONE
        }

        // The rich per-stage trace — the actual "why" (HANDSHAKE_FAILED, etc.). Persisted on
        // every line, so it's here even when the on-failure dialog never appeared on the device.
        if (trace != null && trace.text.isNotBlank()) {
            traceHeader.text = if (trace.savedAtMillis > 0L) {
                val saved = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(trace.savedAtMillis))
                "${getString(R.string.diagnostics_trace_header)} · " +
                    getString(R.string.diagnostics_trace_saved, saved)
            } else {
                getString(R.string.diagnostics_trace_header)
            }
            traceHeader.visibility = View.VISIBLE
            traceView.text = trace.text
            traceView.visibility = View.VISIBLE
        } else {
            traceHeader.visibility = View.GONE
            traceView.visibility = View.GONE
        }

        shareBtn.visibility = View.VISIBLE
        shareBtn.setOnClickListener { shareLog(diag, trace) }
    }

    private fun rowText(row: OffGridRepository.AttemptRow): String {
        val sb = StringBuilder("${row.seq + 1}. ${row.tierName} — ${outcomeLabel(row.outcome)}")
        row.failureCode?.let { FailureCode.fromId(it) }?.takeIf { it != FailureCode.NONE }
            ?.let { sb.append(" · ${it.name}") }
        row.durationMs?.let { sb.append(" · $it ms") }
        return sb.toString()
    }

    private fun outcomeLabel(outcome: Int): String = when (outcome) {
        AttemptOutcome.CONNECTED -> getString(R.string.diagnostics_outcome_connected)
        AttemptOutcome.FAILED -> getString(R.string.diagnostics_outcome_failed)
        AttemptOutcome.SKIPPED -> getString(R.string.diagnostics_outcome_skipped)
        else -> getString(R.string.diagnostics_outcome_pending)
    }

    private fun rowView(text: String): TextView {
        val tv = TextView(requireContext())
        tv.text = text
        tv.textSize = 14f
        tv.setTextColor(themeColor(R.attr.appTextPrimaryColor))
        val pad = (8 * resources.displayMetrics.density).toInt()
        tv.setPadding(0, pad, 0, pad)
        return tv
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun shareLog(
        diag: OffGridRepository.Diagnostics?,
        trace: TransportDiag.PersistedTrace?
    ) {
        val ctx = requireContext()
        val summary = buildString {
            if (diag != null) {
                append(sessionHeader.text).append("\n\n")
                for (row in diag.attempts) append(rowText(row)).append("\n")
            }
            if (trace != null && trace.text.isNotBlank()) {
                append("\n").append(getString(R.string.diagnostics_trace_header)).append("\n")
                append(trace.text).append("\n")
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_title))
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        try {
            // provider_paths.xml exposes cacheDir (cache-path), so copy the JSONL
            // out of filesDir before handing it to the FileProvider.
            val src = File(ctx.filesDir, TransportAttemptLog.FILE_NAME)
            if (src.exists() && src.length() > 0) {
                val cacheCopy = File(ctx.cacheDir, TransportAttemptLog.FILE_NAME)
                src.copyTo(cacheCopy, overwrite = true)
                val uri = appFileProviderUri(ctx, cacheCopy)
                send.putExtra(Intent.EXTRA_STREAM, uri)
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {}
        startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
    }
}
