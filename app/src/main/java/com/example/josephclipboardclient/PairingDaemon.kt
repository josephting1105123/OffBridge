package com.EntropicSoftwareLab.ClipboardShare

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

object PairingDaemon {
    // var so [stop] can cancel and [start] can rebuild (a cancelled scope can't relaunch).
    private var daemonJob = Job()
    private var daemonScope = CoroutineScope(Dispatchers.IO + daemonJob)
    @Volatile var isRunning = false
    @Volatile private var registeredContext: Context? = null

    const val ACTION_PAIRING_ACCEPTED = "com.EntropicSoftwareLab.ClipboardShare.PAIRING_ACCEPTED"
    const val ACTION_PAIRING_DECLINED = "com.EntropicSoftwareLab.ClipboardShare.PAIRING_DECLINED"
    const val ACTION_TRIGGER_PAIRING_NOTIFICATION = "com.EntropicSoftwareLab.ClipboardShare.TRIGGER_PAIRING_NOTIFICATION"

    private val pendingConfirmations = ConcurrentHashMap<String, PrintWriter>()

    private val confirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val senderIp = intent?.getStringExtra("SENDER_IP") ?: return
            val writer = pendingConfirmations.remove(senderIp) ?: return

            daemonScope.launch {
                try {
                    if (intent.action == ACTION_PAIRING_ACCEPTED) {
                        writer.println("PAIR_ACCEPT")
                    } else {
                        writer.println("PAIR_DECLINE")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    writer.close()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        if (!daemonScope.isActive) {
            daemonJob = Job()
            daemonScope = CoroutineScope(Dispatchers.IO + daemonJob)
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_PAIRING_ACCEPTED)
            addAction(ACTION_PAIRING_DECLINED)
        }

        // Register on (and remember) the application context so [stop] can unregister it later
        // without holding an Activity/Service reference.
        val appCtx = context.applicationContext
        registeredContext = appCtx
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(confirmationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appCtx.registerReceiver(confirmationReceiver, filter)
        }

        daemonScope.launch {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress("0.0.0.0", Ports.PAIRING))

                while (isActive) {
                    try {
                        val clientSocket = serverSocket.accept()
                        handleIncomingPairing(context, clientSocket)
                    } catch (e: Exception) {
                        // A transient accept() error must not permanently kill pairing;
                        // only stop when the socket itself is closed.
                        if (!isActive || serverSocket.isClosed) break
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                serverSocket?.close()
            }
        }
    }

    /**
     * Counterpart to [start]: cancels the pairing accept loop and unregisters the confirmation
     * receiver so neither outlives the process intent. Idempotent; a later [start] rebinds cleanly.
     * Call on a genuine app shutdown (Power Off).
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try { daemonScope.cancel() } catch (_: Exception) {}
        try { registeredContext?.unregisterReceiver(confirmationReceiver) } catch (_: Exception) {}
        registeredContext = null
    }

    private fun handleIncomingPairing(context: Context, socket: Socket) {
        daemonScope.launch {
            try {
                // Bound the handshake read so a peer that connects but never sends a request
                // can't pin this coroutine/socket forever (writes on the parked PAIR_REQ
                // socket are unaffected — soTimeout only applies to reads).
                socket.soTimeout = 20000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                val senderIp = (socket.remoteSocketAddress as InetSocketAddress).address.hostAddress

                val request = reader.readLine()

                if (request != null && request.startsWith("UNPAIR_REQ|")) {
                    // Authenticated unpair: only honor it if the sender proves possession of the
                    // existing per-node key (HMAC over a fresh nonce). Stops any host on the LAN
                    // from silently removing a trusted device.
                    val key = SecurityVault.getKeyForNode(context, senderIp)
                    if (key == null) { socket.close(); return@launch }
                    val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    writer.println("UNPAIR_CHALLENGE|${Base64.encodeToString(nonce, Base64.NO_WRAP)}")
                    val proofLine = reader.readLine()
                    if (proofLine != null && proofLine.startsWith("UNPAIR_PROOF|")) {
                        val proof = try {
                            Base64.decode(proofLine.removePrefix("UNPAIR_PROOF|"), Base64.NO_WRAP)
                        } catch (_: Exception) { null }
                        if (proof != null && java.security.MessageDigest.isEqual(proof, unpairHmac(key, nonce))) {
                            SecurityVault.removeTrustedNode(context, senderIp)
                            context.sendBroadcast(Intent("com.EntropicSoftwareLab.ClipboardShare.UNPAIRED_REMOTELY").setPackage(context.packageName))
                        }
                    }
                    socket.close()
                    return@launch
                }

                if (request != null && request.startsWith("PAIR_REQ|")) {
                    val parts = request.split("|")
                    val senderName = parts[1]
                    val peerPubKey = Base64.decode(parts[2], Base64.NO_WRAP)

                    val ecdh = EcdhSession()
                    val myPubKey = ecdh.getPublicKey()
                    val (aesKey, visualPin) = ecdh.deriveSecrets(peerPubKey)

                    writer.println("PAIR_ACK|${Base64.encodeToString(myPubKey, Base64.NO_WRAP)}")

                    // A second PAIR_REQ from the same IP overwrites the map entry; close the
                    // previously parked writer/socket so it isn't leaked forever.
                    pendingConfirmations.put(senderIp, writer)?.let { try { it.close() } catch (_: Exception) {} }

                    triggerPairingNotificationBroadcast(context, senderName, senderIp, aesKey, visualPin)
                }
                // Socket intentionally left open: the PAIR_REQ path parks its PrintWriter in
                // pendingConfirmations until the user accepts/declines, so do NOT finally-close here.
            } catch (e: Exception) {
                e.printStackTrace()
                socket.close()
            }
        }
    }

    private fun triggerPairingNotificationBroadcast(context: Context, senderName: String, senderIp: String, aesKey: ByteArray, visualPin: String) {
        val broadcastIntent = Intent(ACTION_TRIGGER_PAIRING_NOTIFICATION).apply {
            setPackage(context.packageName)
            putExtra("SENDER_NAME", senderName)
            putExtra("SENDER_IP", senderIp)
            putExtra("AES_KEY", aesKey)
            putExtra("VISUAL_PIN", visualPin)
        }
        context.sendBroadcast(broadcastIntent)
    }

    fun sendUnpairRequest(context: Context, targetIp: String) {
        // Capture the key NOW (synchronously) before the caller removes the local trusted node —
        // it's needed to answer the receiver's unpair challenge.
        val key = SecurityVault.getKeyForNode(context, targetIp)
        daemonScope.launch {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 3000
                    socket.connect(InetSocketAddress(targetIp, Ports.PAIRING), 2000)
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    writer.println("UNPAIR_REQ|")
                    writer.flush()
                    if (key != null) {
                        val challenge = reader.readLine()
                        if (challenge != null && challenge.startsWith("UNPAIR_CHALLENGE|")) {
                            val nonce = Base64.decode(challenge.removePrefix("UNPAIR_CHALLENGE|"), Base64.NO_WRAP)
                            writer.println("UNPAIR_PROOF|${Base64.encodeToString(unpairHmac(key, nonce), Base64.NO_WRAP)}")
                            writer.flush()
                        }
                    }
                    kotlinx.coroutines.delay(200) // Allow time for packet transmission
                }
            } catch (e: Exception) {} // best-effort unpair notify by design
        }
    }

    /** Standard HMAC-SHA256(key, message) — identical on Android and Windows, so unpair proofs verify cross-platform. */
    private fun unpairHmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    suspend fun initiatePairing(context: Context, targetIp: String, myDeviceName: String, onPin: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.soTimeout = 15000
            socket.connect(InetSocketAddress(targetIp, Ports.PAIRING), 3000)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val ecdh = EcdhSession()
            val myPubKey = ecdh.getPublicKey()

            writer.println("PAIR_REQ|$myDeviceName|${Base64.encodeToString(myPubKey, Base64.NO_WRAP)}")

            val ackResponse = reader.readLine()
            if (ackResponse != null && ackResponse.startsWith("PAIR_ACK|")) {
                val peerPubKey = Base64.decode(ackResponse.split("|")[1], Base64.NO_WRAP)
                val (aesKey, visualPin) = ecdh.deriveSecrets(peerPubKey)
                onPin(visualPin)   // surface the MITM-verification code while we wait for the peer to accept

                val finalResponse = reader.readLine()
                if (finalResponse == "PAIR_ACCEPT") {
                    SecurityVault.saveTrustedNode(context, targetIp, aesKey)
                    return@withContext "SUCCESS"
                } else {
                    return@withContext "DECLINED"
                }
            }
        } catch (e: SocketTimeoutException) {
            return@withContext "TIMEOUT"
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket?.close()
        }
        return@withContext "FAILED"
    }
}