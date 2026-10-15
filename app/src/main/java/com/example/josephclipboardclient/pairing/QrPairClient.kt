package com.EntropicSoftwareLab.ClipboardShare.pairing

import com.EntropicSoftwareLab.ClipboardShare.readLineCapped
import android.content.Context
import android.net.Network
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.Ports
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.transport.PairSeal
import com.EntropicSoftwareLab.ClipboardShare.transport.hotspot.SpecifierJoiner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlin.coroutines.resume

/**
 * Scanner half of QR pairing: takes a decoded [PairQrPayload] and completes the handshake against
 * the device that displayed it.
 *
 * Reachability is tried cheapest-first — the host's LAN address, then whatever address the code
 * advertised, and only if both are dead does it join the host's off-grid AP. Preferring the LAN
 * route is not just about speed: trust records are keyed by address, so pairing over a shared
 * network stores the address the pair will still be reachable at tomorrow, while an AP-subnet
 * address dies with the hotspot.
 */
object QrPairClient {

    sealed class Result {
        /** Both sides stored trust. [peerIp] is the address the pairing was recorded against. */
        data class Success(val peerIp: String, val peerName: String) : Result()

        /** The host had no open window for our token — the code expired or someone else used it. */
        object Denied : Result()

        /** Our own pre-check: the code was already stale before we dialed. */
        object Expired : Result()

        /** Nothing answered on any route, and no AP credentials to fall back to. */
        object Unreachable : Result()

        /** The user declined the OS prompt to join the host's Wi-Fi. */
        object JoinRefused : Result()

        /**
         * The host answered but its key confirmation didn't open. Either something is sitting
         * between us or the code was mixed up — nothing is stored on either side.
         */
        object ConfirmationFailed : Result()

        object Failed : Result()
    }

    private const val DIRECT_CONNECT_TIMEOUT_MS = 2_500
    private const val AP_CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val AP_JOIN_TIMEOUT_MS = 30_000

    /**
     * Runs the full handshake. Safe to call from any dispatcher; all blocking work happens on IO.
     * On success both devices hold the same AES-256 key and each has stored the other.
     */
    suspend fun pair(
        context: Context,
        payload: PairQrPayload,
        myDeviceName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result = withContext(Dispatchers.IO) {
        if (payload.isExpired(nowMs)) return@withContext Result.Expired

        // Refuse our own code. Without this a user who taps Scan on the same device that is showing
        // the QR would pair the phone to itself and land a trust record for its own address.
        if (payload.deviceId == SecurityVault.localDeviceId(context)) return@withContext Result.Failed

        val direct = buildList {
            payload.lanIp?.let { add(it) }
            add(payload.ip)
        }.distinct()

        // A denial on one route is not the end of the attempt. It means that host had no window for
        // our token — so nothing was claimed and nothing was stored, and the address we reached was
        // simply the wrong one (a LAN record the code advertised that another machine has since
        // taken over by DHCP). Every other outcome is terminal: the right host answered.
        //
        // The cost is that a wrong host learns the token. It is worthless to anyone but the host
        // that minted it, which is the only device holding a window it can open.
        var sawDenial = false
        for (host in direct) {
            val socket = connect(SocketFactory.getDefault(), host, payload.port, DIRECT_CONNECT_TIMEOUT_MS)
                ?: continue
            val result = socket.use { handshake(context, it, host, payload, myDeviceName) }
            if (result is Result.Denied) {
                sawDenial = true
                continue
            }
            return@withContext result
        }

        if (payload.hasApCredentials()) {
            val overAp = pairOverAp(context, payload, myDeviceName)
            // Only fall through when the AP route never reached anything; a denial or a refused
            // join there is the more accurate story to tell.
            if (overAp !is Result.Unreachable) return@withContext overAp
        }
        return@withContext if (sawDenial) Result.Denied else Result.Unreachable
    }

    /** Joins the host's AP, pairs over it, then releases the network so the phone goes back home. */
    private suspend fun pairOverAp(
        context: Context,
        payload: PairQrPayload,
        myDeviceName: String,
    ): Result {
        // Re-read the credentials as locals rather than trusting `hasApCredentials()` to have been
        // called: WifiNetworkSpecifier throws on a null or out-of-range passphrase, and a crash in
        // the pairing path would be a worse outcome than reporting the route unusable.
        val ssid = payload.ssid
        val psk = payload.psk
        if (ssid.isNullOrEmpty() || psk.isNullOrEmpty()) return Result.Unreachable

        val joiner = SpecifierJoiner(context)
        try {
            val network = withTimeoutOrNull(AP_JOIN_TIMEOUT_MS.toLong()) {
                suspendCancellableCoroutine<Network?> { cont ->
                    joiner.connect(ssid, psk, AP_JOIN_TIMEOUT_MS, object : SpecifierJoiner.Listener {
                        override fun onNetworkAvailable(network: Network) {
                            if (cont.isActive) cont.resume(network)
                        }

                        override fun onNetworkUnavailable() {
                            if (cont.isActive) cont.resume(null)
                        }

                        // The group can drop after we're in; the pairing socket fails on its own and
                        // reports through the normal path, so this needs no separate resume.
                        override fun onNetworkLost() = Unit
                    })
                    cont.invokeOnCancellation { joiner.teardown() }
                }
            } ?: return Result.JoinRefused

            // Sockets must come from the joined network's factory — the OS does not route to a
            // specifier network by default, so a plain Socket here would silently go out over the
            // phone's home Wi-Fi and time out.
            return withContext(Dispatchers.IO) {
                val socket = connect(network.socketFactory, payload.ip, payload.port, AP_CONNECT_TIMEOUT_MS)
                    ?: return@withContext Result.Unreachable
                socket.use { handshake(context, it, payload.ip, payload, myDeviceName) }
            }
        } finally {
            joiner.teardown()
        }
    }

    private fun connect(factory: SocketFactory, host: String, port: Int, timeoutMs: Int): Socket? = try {
        factory.createSocket().apply {
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = READ_TIMEOUT_MS
        }
    } catch (_: Exception) {
        null
    }

    /**
     * REQ -> ACK -> FIN over an open socket.
     *
     * Ordering is deliberate: we store trust only after the host's confirmation opens under our
     * derived key, and the host stores only after our FIN opens under its own. A FIN lost in flight
     * therefore leaves us trusting a host that doesn't yet trust us — the same one-sided outcome a
     * dropped PAIR_ACCEPT produces in the PIN flow, and recovered the same way, by pairing again.
     */
    private fun handshake(
        context: Context,
        socket: Socket,
        peerIp: String,
        payload: PairQrPayload,
        myDeviceName: String,
    ): Result {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val ecdh = EcdhSession()
            // The AES key is SHA-256 of the ECDH secret either way; the PIN variant is irrelevant
            // here because no human compares a PIN in this flow.
            val (aesKey, _) = ecdh.deriveSecrets(payload.publicKey)

            writer.println(
                QrPairWire.req(myDeviceName, ecdh.getPublicKey(), payload.token, SecurityVault.localDeviceId(context))
            )

            val response = reader.readLineCapped() ?: return Result.Failed
            QrPairWire.parseDeny(response)?.let { return Result.Denied }

            val ack = QrPairWire.parseAck(response) ?: return Result.Failed
            val tokenHex = payload.tokenHex()
            if (!PairSeal.verify(aesKey, ack.seal, PairSeal.hostConfirmation(tokenHex), System.currentTimeMillis())) {
                return Result.ConfirmationFailed
            }

            // Proven: the host derived the same key, so it holds the private half of the key printed
            // in the code the user physically scanned. Record trust, then tell it we did.
            SecurityVault.saveTrustedNode(context, peerIp, aesKey, ack.deviceId ?: payload.deviceId, payload.name)
            writer.println(
                QrPairWire.fin(PairSeal.seal(aesKey, PairSeal.peerConfirmation(tokenHex), System.currentTimeMillis()))
            )
            writer.flush()

            return Result.Success(peerIp, payload.name)
        } catch (_: IllegalArgumentException) {
            // deriveSecrets rejects a malformed or off-curve key from the code.
            return Result.Failed
        } catch (_: Exception) {
            return Result.Failed
        }
    }
}
