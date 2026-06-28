package com.joseph.clipboard.windows.network

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

class FileTransferDaemon(
    private val onOfferReceived: (ip: String, sessionId: String, deviceName: String, manifest: List<Pair<String, Long>>) -> Unit,
    private val onAcceptReceived: (sessionId: String, fgcm: Boolean, socket: Socket) -> Unit,
    private val onResumeReceived: (sessionId: String, fileIndex: Int, offset: Long, ackExpected: Boolean, fgcm: Boolean, socket: Socket) -> Unit,
    private val onCancelReceived: (sessionId: String) -> Unit = {},
    /** An Android scanner dialed in for off-grid "send via QR"; the handler owns the socket. */
    private val onP2pHelloReceived: (socket: Socket, header: String) -> Unit = { s, _ -> try { s.close() } catch (_: Exception) {} }
) {
    private val FILE_TRANSFER_PORT = Ports.FILE_TRANSFER
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(isDaemon = true, name = "FileTransfer-Listener") {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(FILE_TRANSFER_PORT))
                }
                println("FileTransferDaemon started on port $FILE_TRANSFER_PORT")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleIncomingConnection(clientSocket)
                }
            } catch (e: SocketException) {
                if (isRunning) e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stop()
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

    private fun handleIncomingConnection(clientSocket: Socket) {
        thread(isDaemon = true, name = "FileTransfer-Handler-${clientSocket.inetAddress.hostAddress}") {
            try {
                // Bound the header read so a peer that connects but never sends a newline
                // can't leak this handler thread forever (only affects reads; the streaming
                // hand-off paths write, so this default is safe and can be overridden later).
                clientSocket.soTimeout = 30000
                val inputStream = clientSocket.getInputStream()
                val header = Net.readLineUtf8(inputStream)

                if (header.startsWith("OFFER|")) {
                    val parts = header.split("|")
                    if (parts.size >= 4) {
                        val sessionId = parts[1]
                        val deviceName = parts[2]
                        val manifestLine = Net.readLineUtf8(inputStream)
                        
                        val manifest = mutableListOf<Pair<String, Long>>()
                        if (manifestLine.startsWith("MANIFEST|")) {
                            val items = manifestLine.substringAfter("MANIFEST|").split("|").filter { it.isNotBlank() }
                            for (item in items) {
                                val split = item.split(":")
                                if (split.size >= 2) {
                                    val name = split[0]
                                    val size = split[1].toLongOrNull() ?: 0L
                                    manifest.add(Pair(name, size))
                                }
                            }
                        }
                        
                        clientSocket.getOutputStream().write("OFFER_RECEIVED\n".toByteArray(Charsets.UTF_8))
                        clientSocket.getOutputStream().flush()
                        
                        onOfferReceived(clientSocket.inetAddress.hostAddress, sessionId, deviceName, manifest)
                    }
                    clientSocket.close()
                } else if (header.startsWith("ACCEPT_OFFER|")) {
                    val parts = header.split("|")
                    val sessionId = parts[1]
                    // Receiver appended "fgcm" ⇒ it wants the bulk stream FileSeal-sealed.
                    val fgcm = parts.drop(2).contains("fgcm")
                    // Hand off socket to app controller to stream files out
                    onAcceptReceived(sessionId, fgcm, clientSocket)
                } else if (header.startsWith("RESUME_REQUEST|")) {
                    val parts = header.split("|")
                    if (parts.size >= 4) {
                        val sessionId = parts[1]
                        val fileIndex = parts[2].toIntOrNull() ?: 0
                        val offset = parts[3].toLongOrNull() ?: 0L
                        // Receiver flags after the offset, order-independent: "ack" (COMPLETION_ACK)
                        // and/or "fgcm" (FileSeal-sealed bulk stream).
                        val flags = parts.drop(4)
                        val ackExpected = flags.contains("ack")
                        val fgcm = flags.contains("fgcm")
                        onResumeReceived(sessionId, fileIndex, offset, ackExpected, fgcm, clientSocket)
                    } else {
                        clientSocket.close()
                    }
                } else if (header.startsWith("CANCEL_TRANSFER|")) {
                    val parts = header.split("|")
                    if (parts.size >= 2) onCancelReceived(parts[1])
                    clientSocket.close()
                } else if (header.startsWith("DECLINE_OFFER|")) {
                    val parts = header.split("|")
                    if (parts.size >= 2) onCancelReceived(parts[1])
                    clientSocket.close()
                } else if (header.startsWith("P2P_HELLO|")) {
                    // Off-grid "send via QR" (3.0): an Android phone scanned our QR and
                    // dialed in. Hand the socket to the controller's QR host, which runs
                    // the handshake and streams the files — it owns the socket lifecycle.
                    onP2pHelloReceived(clientSocket, header)
                } else if (header.startsWith("P2P_BUSY")) {
                    // P2P_BUSY is a receiver-side reply; Windows is the sender, so ignore.
                    clientSocket.close()
                } else {
                    System.err.println("Unknown transfer protocol header: $header")
                    clientSocket.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try { clientSocket.close() } catch (e: Exception) {}
            }
        }
    }
}
