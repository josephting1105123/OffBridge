package com.joseph.clipboard.windows.network

import java.io.InputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

class ClipboardDaemon(
    private val acceptIncomingProvider: () -> Boolean,
    private val onClipboardReceived: (String) -> Unit,
    private val onClipboardDeleted: (String) -> Unit
) {
    val CLIPBOARD_PORT = Ports.CLIPBOARD
    private var isRunning = false
    // Written by the accept loop, read/closed by stop() from another thread — @Volatile so stop()
    // observes the bound socket and can actually close it (else the listener can stay bound).
    @Volatile private var serverSocket: ServerSocket? = null

    // Upper bound on a single clipboard payload (base64 chars) — generous for real text
    // copies while preventing an unbounded read from exhausting the heap.
    private val MAX_CLIPBOARD_CHARS = 16 * 1024 * 1024

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(isDaemon = true, name = "Clipboard-Listener") {
            // Capture the socket this run bound into a local. The finally must close only THIS
            // socket — calling stop() would close whatever is in the serverSocket field, which a
            // fast stop()/start() cycle may have already replaced with a freshly bound socket.
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(CLIPBOARD_PORT))
            }
            serverSocket = socket
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
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
    }

    private fun handleIncomingClipboard(clientSocket: Socket) {
        thread(isDaemon = true, name = "Clipboard-Handler-${clientSocket.inetAddress.hostAddress}") {
            try {
                clientSocket.soTimeout = 10000
                if (!acceptIncomingProvider()) {
                    println("Rejected clipboard sync from ${clientSocket.inetAddress.hostAddress} (Accept incoming is OFF)")
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
                    val senderIp = clientSocket.inetAddress.hostAddress
                    val aesKey = com.joseph.clipboard.windows.storage.SecurityVault.getAesKey(senderIp)
                    if (aesKey != null) {
                        try {
                            // decryptString accepts only sealed (AES-GCM) payloads since the 3.5.0
                            // CBC retirement; senderIp just names the peer in the rejection error.
                            val decryptedText = com.joseph.clipboard.windows.storage.CryptoEngine.decryptString(base64Payload, aesKey, senderIp)
                            if (decryptedText.startsWith("CMD_DELETE|")) {
                                // Remote deleted a history entry. Android sends this as an encrypted
                                // "CMD_DELETE|<text>" control frame; the suffix must NOT be pasted or
                                // re-added to history. Remove any matching history row (parity with
                                // Android's ClipboardHistoryManager.removeEntry) and wipe it from the
                                // OS clipboard if it still matches.
                                val toDelete = decryptedText.substring("CMD_DELETE|".length)
                                onClipboardDeleted(toDelete)
                                val current = com.joseph.clipboard.windows.os.ClipboardManager.readText()
                                if (current == toDelete) {
                                    com.joseph.clipboard.windows.os.ClipboardManager.writeText("")
                                }
                            } else {
                                com.joseph.clipboard.windows.os.ClipboardManager.writeText(decryptedText)
                                onClipboardReceived(decryptedText)
                            }
                        } catch (e: Exception) {
                            System.err.println("Failed to decrypt clipboard payload: ${e.message}")
                        }
                    } else {
                        System.err.println("No AES key found for $senderIp, cannot decrypt clipboard.")
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
