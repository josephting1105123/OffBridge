package com.joseph.clipboard.windows.os

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.FlavorListener
import java.awt.datatransfer.FlavorEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

object ClipboardManager {
    // Clipboard watcher timings (ms). Named so the settle/poll/backoff/retry cadence is legible.
    private const val SETTLE_DELAY_MS = 80L        // let content settle after Ctrl+C before reading
    // Fallback poll cadence. The FlavorListener already delivers real changes instantly; this loop
    // only catches missed events, yet each tick copies the ENTIRE clipboard text out of the OS
    // (multi-MB for a large copied block) — so it runs at 1s, not the old 300ms, cutting the
    // steady-state copy churn ~3x while keeping the safety net.
    private const val POLL_INTERVAL_MS = 1000L
    private const val POLL_ERROR_BACKOFF_MS = 500L // pause after an unexpected poll error
    private const val READ_RETRY_ATTEMPTS = 10     // getData retries while another app holds the clipboard
    private const val READ_RETRY_DELAY_MS = 100L   // pause between read retries

    private val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    @Volatile
    private var lastClipboardText: String = ""
    @Volatile
    private var isPolling = false
    // Copy-on-write so notifyListeners (background thread) can iterate while add/removeListener mutate
    // concurrently without a ConcurrentModificationException.
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private var pollingThread: Thread? = null
    private var flavorListenerRegistered = false
    // FlavorListener fires on the AWT EDT (the Compose render thread). The settle-delay + readText retry
    // loop can block for ~1s, so hand the work to a single background thread instead of sleeping on the EDT.
    private val flavorWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Clipboard-Flavor-Worker").apply { isDaemon = true }
    }

    fun startPolling() {
        if (isPolling) return
        isPolling = true

        // Initialize with current clipboard to avoid firing event immediately
        lastClipboardText = readText() ?: ""

        // Primary detection: FlavorListener (fires on native clipboard change events)
        if (!flavorListenerRegistered) {
            try {
                clipboard.addFlavorListener(object : FlavorListener {
                    override fun flavorsChanged(e: FlavorEvent?) {
                        // Runs on the AWT EDT (Compose render thread) — do NOT sleep/read here. Hand the
                        // settle-delay + clipboard read off to a background worker so the UI never stalls.
                        flavorWorker.execute {
                            // Small delay to let the clipboard content settle after Ctrl+C
                            try { Thread.sleep(SETTLE_DELAY_MS) } catch (_: InterruptedException) {}
                            handleClipboardChange()
                        }
                    }
                })
                flavorListenerRegistered = true
            } catch (e: Exception) {
                System.err.println("ClipboardManager: Failed to register FlavorListener: ${e.message}")
            }
        }

        // Fallback detection: Polling thread (catches changes FlavorListener might miss)
        startPollingThread()
    }

    private fun startPollingThread() {
        pollingThread = thread(isDaemon = true, name = "Clipboard-Poller") {
            while (isPolling) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                    handleClipboardChange()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // Keep polling alive even if something unexpected happens
                    System.err.println("ClipboardManager: Polling error: ${e.message}")
                    try { Thread.sleep(POLL_ERROR_BACKOFF_MS) } catch (_: InterruptedException) { break }
                }
            }

            // Watchdog: auto-restart if still supposed to be polling
            if (isPolling) {
                System.err.println("ClipboardManager: Polling thread died unexpectedly — restarting")
                startPollingThread()
            }
        }
    }

    private fun handleClipboardChange() {
        try {
            val currentText = readText()
            if (currentText != null && currentText != lastClipboardText && currentText.isNotBlank()) {
                lastClipboardText = currentText
                notifyListeners(currentText)
            }
        } catch (e: Exception) {
            // Silently ignore — will retry on next poll/event
        }
    }

    fun stopPolling() {
        isPolling = false
        pollingThread?.interrupt()
        pollingThread = null
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(text: String) {
        listeners.forEach { 
            try {
                it.invoke(text)
            } catch (e: Exception) {
                System.err.println("ClipboardManager: Listener error: ${e.message}")
            }
        }
    }

    fun readText(): String? {
        for (i in 1..READ_RETRY_ATTEMPTS) {
            try {
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    return clipboard.getData(DataFlavor.stringFlavor) as? String
                }
                return null
            } catch (e: Exception) {
                try { Thread.sleep(READ_RETRY_DELAY_MS) } catch (ie: InterruptedException) { break }
            }
        }
        return null
    }

    fun writeText(text: String) {
        try {
            val selection = StringSelection(text)
            clipboard.setContents(selection, selection)
            lastClipboardText = text // update so we don't trigger our own listener
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
