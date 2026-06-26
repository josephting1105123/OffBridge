package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.storage.CryptoEngine
import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.SecurityVault
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PairingDaemon(
    private val onIncomingPairingRequest: (senderIp: String, senderName: String, visualPin: String, accept: () -> Unit, decline: () -> Unit) -> Unit,
    private val onUnpaired: ((senderIp: String) -> Unit)? = null
) {
    private val PAIRING_PORT = Ports.PAIRING
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(isDaemon = true, name = "Pairing-Listener") {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PAIRING_PORT))
                }
                println("PairingDaemon started on port $PAIRING_PORT")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleIncomingPairing(clientSocket)
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

    private fun handleIncomingPairing(clientSocket: Socket) {
        thread(isDaemon = true, name = "Pairing-Handler-${clientSocket.inetAddress.hostAddress}") {
            try {
                // Bound the handshake read so a peer that connects but never sends a request
                // can't leak this handler thread forever. Only affects reads, so the later
                // PAIR_ACK/ACCEPT writes and the 2-minute dialog latch are unaffected.
                clientSocket.soTimeout = 20000
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                val senderIp = clientSocket.inetAddress.hostAddress

                val request = reader.readLine() ?: return@thread
                
                if (request.startsWith("UNPAIR_REQ|")) {
                    // Authenticated unpair: require the sender to prove possession of the per-node key
                    // (HMAC over a fresh nonce) before removing it. Blocks unauthenticated unpair.
                    val key = SecurityVault.getAesKey(senderIp)
                    if (key == null) { clientSocket.close(); return@thread }
                    val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    writer.println("UNPAIR_CHALLENGE|${Base64.getEncoder().encodeToString(nonce)}")
                    val proofLine = reader.readLine()
                    if (proofLine != null && proofLine.startsWith("UNPAIR_PROOF|")) {
                        val proof = try {
                            Base64.getDecoder().decode(proofLine.removePrefix("UNPAIR_PROOF|"))
                        } catch (e: Exception) { null }
                        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                        val expected = mac.doFinal(nonce)
                        if (proof != null && java.security.MessageDigest.isEqual(proof, expected)) {
                            println("Authenticated unpair from $senderIp — removing trusted node")
                            SecurityVault.removeTrustedNode(senderIp)
                            onUnpaired?.invoke(senderIp)
                        }
                    }
                    clientSocket.close()
                    return@thread
                }

                if (request.startsWith("PAIR_REQ|")) {
                    val parts = request.split("|")
                    if (parts.size >= 3) {
                        val senderName = parts[1]
                        val peerPubKey = Base64.getDecoder().decode(parts[2])

                        val ecdh = EcdhSession()
                        val myPubKey = ecdh.getPublicKey()
                        val (aesKey, visualPin) = ecdh.deriveSecrets(peerPubKey)

                        writer.println("PAIR_ACK|${Base64.getEncoder().encodeToString(myPubKey)}")

                        val latch = CountDownLatch(1)
                        var isAccepted = false

                        onIncomingPairingRequest(senderIp, senderName, visualPin, {
                            isAccepted = true
                            latch.countDown()
                        }, {
                            isAccepted = false
                            latch.countDown()
                        })

                        // A never-answered pairing dialog must not pin this thread+socket forever.
                        if (!latch.await(2, TimeUnit.MINUTES)) {
                            isAccepted = false
                        }

                        if (isAccepted) {
                            writer.println("PAIR_ACCEPT")
                            SecurityVault.saveTrustedNode(senderIp, aesKey)
                            SecurityVault.savePairedDeviceName(senderIp, senderName)
                            println("Pairing accepted with $senderName ($senderIp)")
                        } else {
                            writer.println("PAIR_DECLINE")
                            println("Pairing declined with $senderName ($senderIp)")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    clientSocket.close()
                } catch (e: Exception) {}
            }
        }
    }
}
