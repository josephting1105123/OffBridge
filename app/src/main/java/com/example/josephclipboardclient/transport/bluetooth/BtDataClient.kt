package com.EntropicSoftwareLab.ClipboardShare.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.transport.BluetoothSocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import java.io.IOException
import java.util.Collections
import java.util.UUID

/**
 * Tier T4 dialer, receiver side (§3.2): connects an insecure L2CAP CoC to the
 * PSM relayed in TRY_TRANSPORT(BT_L2CAP), falling back to the token-derived
 * RFCOMM service record. The [BluetoothDevice] is the very advertiser the BLE
 * control client matched — no discovery phase exists on this tier, which is
 * why it cannot "fail to find the device" once the control channel is up.
 *
 * Blocking; call from a background thread.
 */
object BtDataClient {

    private const val TAG = "OffGridBt"

    // In-flight dial sockets. BluetoothSocket.connect() blocks with no timeout and
    // cannot be cancelled by a parameter, so the only way to unblock a parked dial is
    // to close its socket from another thread. teardown() does that via cancelPending().
    private val pendingSockets = Collections.synchronizedSet(mutableSetOf<BluetoothSocket>())

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(device: BluetoothDevice, psm: Int, rfcommUuid: UUID): TransportConnection {
        if (psm > 0) {
            val socket = device.createInsecureL2capChannel(psm)
            pendingSockets.add(socket)
            try {
                socket.connect()
                pendingSockets.remove(socket)
                return BluetoothSocketConnection(socket)
            } catch (e: Exception) {
                // Close the half-open socket — falling through to RFCOMM without this
                // leaked the L2CAP socket (a kernel BT handle) on every fallback.
                pendingSockets.remove(socket)
                try { socket.close() } catch (_: Exception) {}
                Log.i(TAG, "L2CAP dial failed (psm=$psm): ${e.message} — trying RFCOMM")
            }
        }
        val socket = device.createInsecureRfcommSocketToServiceRecord(rfcommUuid)
        pendingSockets.add(socket)
        try {
            socket.connect()
            pendingSockets.remove(socket)
            return BluetoothSocketConnection(socket)
        } catch (e: Exception) {
            pendingSockets.remove(socket)
            try { socket.close() } catch (_: Exception) {}
            throw IOException("Bluetooth dial failed (L2CAP psm=$psm, RFCOMM)", e)
        }
    }

    /**
     * Closes any socket currently blocked in [connect], so a torn-down session's
     * `bt-standby-dial` / `bt-data-dial` thread unblocks (connect() throws) instead of
     * parking forever holding a BT socket. Safe to call repeatedly.
     */
    fun cancelPending() {
        synchronized(pendingSockets) {
            for (s in pendingSockets) {
                try { s.close() } catch (_: Exception) {}
            }
            pendingSockets.clear()
        }
    }
}
