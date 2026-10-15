package com.joseph.clipboard.windows.network

import java.io.InputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import com.joseph.clipboard.windows.os.ClipboardManager
import com.joseph.clipboard.windows.storage.CryptoEngine
import com.joseph.clipboard.windows.storage.DiagLog
import com.joseph.clipboard.windows.storage.SecurityVault

class ClipboardDaemon(
    private val acceptIncomingProvider: () -> Boolean,
    private val onClipboardReceived: (String) -> Unit,
    private val onClipboardDeleted: (String) -> Unit
) {
    val CLIPBOARD_PORT = Ports.CLIPBOARD
    // @Volatile: written by start()/stop() on the caller's thread, read by the accept loop.
    @Volatile private var isRunning = false
    // Written by start(), read/closed by stop() from another thread — @Volatile so stop()
    // observes the bound socket and can actually close it (else the listener can stay bound).
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var listener: Thread? = null

    /** Last bind failure, or null while the listener is up. */
    @Volatile var lastBindError: String? = null
        private set

    /** True while the port is bound and the accept loop is live. */
    val isAlive: Boolean get() = isRunning

    // Upper bound on a single clipboard payload (base64 chars) — generous for real text
    // copies while preventing an unbounded read from exhausting the heap.
    private val MAX_CLIPBOARD_CHARS = 16 * 1024 * 1024

    @Synchronized
    fun start() {
        if (isRunning) return

        // Bind on the CALLER's thread, and before arming isRunning. This bind used to sit inside the
        // worker thread OUTSIDE its try block, so a BindException (port still held moments after a
        // Restart Services) killed the thread with an uncaught exception while isRunning stayed true
        // — every later start() then returned at the guard above, and incoming clipboard sync was
        // dead for the rest of the process's life with nothing reported anywhere.
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(CLIPBOARD_PORT))
            }
        } catch (e: Exception) {
            lastBindError = e.message ?: e.toString()
            System.err.println("ClipboardDaemon failed to bind port $CLIPBOARD_PORT: ${e.message}")
            DiagLog.w("clipboard: bind of TCP $CLIPBOARD_PORT failed (${e.message}) — incoming clipboard sync stays off until a later start succeeds.")
            return
        }
        serverSocket = socket
        lastBindError = null
        isRunning = true

        listener = thread(isDaemon = true, name = "Clipboard-Listener") {
            // The loop closes over the socket THIS run bound. The finally must close only that
            // instance — closing the field would let a late-exiting old thread kill a socket a
            // fresh start() has since published.
            try {
                println("ClipboardDaemon started on port $CLIPBOARD_PORT")

                while (isRunning) {
                    val clientSocket = socket.accept()
                    handleIncomingClipboard(clientSocket)
                }
            } catch (e: SocketException) {
                if (isRunning) e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore
                }
                // If the loop fell out while still flagged running, it died on its own rather than
                // via stop() — clear the flag so a later start() rebinds instead of returning at its
                // already-running guard. Without this the catch-all above is a second route into the
                // exact silent-death this class was just fixed for. The identity check means a normal
                // stop() (which nulls the field first) never takes this branch, so no lock is needed.
                if (serverSocket === socket && isRunning) {
                    isRunning = false
                    serverSocket = null
                    lastBindError = "listener exited unexpectedly"
                    DiagLog.w("clipboard: listener thread exited unexpectedly — incoming clipboard sync is off until the next start")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        // Wait for the accept loop to actually leave before returning, so a restart's rebind can't
        // race an old listener that still holds the port.
        val leaving = listener
        listener = null
        if (leaving != null) runCatching { leaving.join(WORKER_JOIN_TIMEOUT_MS) }
    }

    private fun handleIncomingClipboard(clientSocket: Socket) {
        thread(isDaemon = true, name = "Clipboard-Handler-${clientSocket.inetAddress.hostAddress}") {
            try {
                clientSocket.soTimeout = 10000
                if (!acceptIncomingProvider()) {
                    println("Rejected clipboard sync from ${clientSocket.inetAddress.hostAddress} (Accept incoming is OFF)")
                    return@thread
                }

                // Trust gate before the read (parity with Android ClipboardService): an unpaired host
                // gets dropped here instead of making us buffer up to MAX_CLIPBOARD_CHARS first.
                val senderIp = clientSocket.inetAddress.hostAddress
                val aesKey = SecurityVault.getAesKey(senderIp)
                if (aesKey == null) {
                    println("Rejected clipboard sync from $senderIp (not paired)")
                    return@thread
                }

                // Read full payload, but bounded: reader.readText() reads until EOF with
                // no cap, so a peer that streams an endless/huge payload would OOM the JVM.
                val inputStream: InputStream = clientSocket.getInputStream()
                val reader = BufferedReader(InputStreamReader(inputStream))
                val sb = StringBuilder()
                val chunk = CharArray(8192)
                var total = 0
                while (true) {
                    val n = reader.read(chunk)
                    if (n == -1) break
                    total += n
                    if (total > MAX_CLIPBOARD_CHARS) throw java.io.IOException("Clipboard payload exceeded $MAX_CLIPBOARD_CHARS chars")
                    sb.append(chunk, 0, n)
                }
                val base64Payload = sb.toString().trim()

                if (base64Payload.isNotEmpty()) {
                    try {
                        // decryptString accepts only sealed (AES-GCM) payloads since the 3.5.0
                        // CBC retirement; senderIp just names the peer in the rejection error.
                        val decryptedText = CryptoEngine.decryptString(base64Payload, aesKey, senderIp)
                        if (decryptedText.startsWith("CMD_DELETE|")) {
                            // Remote deleted a history entry. Android sends this as an encrypted
                            // "CMD_DELETE|<text>" control frame; the suffix must NOT be pasted or
                            // re-added to history. Remove any matching history row (parity with
                            // Android's ClipboardHistoryManager.removeEntry) and wipe it from the
                            // OS clipboard if it still matches.
                            val toDelete = decryptedText.substring("CMD_DELETE|".length)
                            onClipboardDeleted(toDelete)
                            val current = ClipboardManager.readText()
                            if (current == toDelete) {
                                ClipboardManager.writeText("")
                            }
                        } else {
                            ClipboardManager.writeText(decryptedText)
                            onClipboardReceived(decryptedText)
                        }
                    } catch (e: Exception) {
                        System.err.println("Failed to decrypt clipboard payload: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    clientSocket.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}
