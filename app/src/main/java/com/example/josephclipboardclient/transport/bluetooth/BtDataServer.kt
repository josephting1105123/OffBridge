package com.EntropicSoftwareLab.ClipboardShare.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.transport.BluetoothSocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import java.util.UUID

/**
 * Tier T4 listener, sender side (§3.2): an insecure L2CAP CoC server socket
 * opened at session start (different radio — pre-warming it costs nothing),
 * plus an RFCOMM service record under the token-derived UUID as the fallback
 * for stacks with broken CoC. "Insecure" skips the OS pairing dialog; the
 * app-layer ECDH + token + HMAC handshake authenticates, exactly as on TCP.
 *
 * Accepted links are handed to [onConnection] on a background thread; the
 * caller dispatches them into the transfer engine's P2P_HELLO path.
 */
@SuppressLint("MissingPermission")
class BtDataServer(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var l2capServer: BluetoothServerSocket? = null
    private var rfcommServer: BluetoothServerSocket? = null

    /** The dynamic PSM to carry in TRY_TRANSPORT(BT_L2CAP), or -1 when L2CAP is unavailable. */
    @Volatile var psm: Int = -1
        private set

    @Volatile private var stopped = false

    var onConnection: ((TransportConnection) -> Unit)? = null

    companion object {
        private const val TAG = "OffGridBt"

        fun isSupported(context: Context): Boolean {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            return try {
                adapter != null && adapter.isEnabled
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Opens both listeners. Returns true when at least the L2CAP listener is
     * up (RFCOMM is best-effort). Safe to call with BT off — returns false.
     */
    fun start(rfcommUuid: UUID): Boolean {
        val ad = adapter ?: return false
        stopped = false
        try {
            if (!ad.isEnabled) return false
            val l2cap = ad.listenUsingInsecureL2capChannel()
            l2capServer = l2cap
            psm = l2cap.psm
            acceptLoop(l2cap, "l2cap")
        } catch (e: Exception) {
            Log.i(TAG, "L2CAP listen failed: ${e.message}")
            psm = -1
        }
        try {
            val rfcomm = ad.listenUsingInsecureRfcommWithServiceRecord("ClipboardShareOffGrid", rfcommUuid)
            rfcommServer = rfcomm
            acceptLoop(rfcomm, "rfcomm")
        } catch (e: Exception) {
            Log.i(TAG, "RFCOMM listen failed: ${e.message}")
        }
        return psm > 0 || rfcommServer != null
    }

    private fun acceptLoop(server: BluetoothServerSocket, label: String) {
        Thread({
            while (!stopped) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    if (!stopped) Log.i(TAG, "$label accept ended: ${e.message}")
                    return@Thread
                }
                Log.i(TAG, "$label link from ${socket.remoteDevice?.address}")
                onConnection?.invoke(BluetoothSocketConnection(socket))
            }
        }, "bt-$label-accept").start()
    }

    /** Idempotent. Live transfers keep their already-accepted sockets. */
    fun stop() {
        stopped = true
        try { l2capServer?.close() } catch (_: Exception) {}
        l2capServer = null
        try { rfcommServer?.close() } catch (_: Exception) {}
        rfcommServer = null
        psm = -1
    }
}
