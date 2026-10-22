package com.EntropicSoftwareLab.ClipboardShare.transport.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import java.util.ArrayDeque
import java.util.UUID

/**
 * Sender-side control endpoint (§4): legacy connectable advertisement carrying
 * the token-derived 128-bit service UUID (16-byte service AD + 3-byte flags —
 * fits the 31-byte legacy budget with no device name), plus a two-
 * characteristic Nordic-UART-style GATT service:
 *
 *   RX (write-no-response, client→server) — [ControlProtocol.CHAR_RX_UUID]
 *   TX (notify, server→client)            — [ControlProtocol.CHAR_TX_UUID]
 *
 * Single client: the first central to connect owns the session; later
 * centrals are disconnected. Until the controller vouches for it with
 * [markAuthenticated], that ownership is provisional: a failed HELLO
 * ([releaseClient]), a disconnect, or [PRE_AUTH_TIMEOUT_MS] of silence frees
 * the slot. Otherwise any nearby central that connected first — the session
 * UUID is visible in the advertisement — could lock the real receiver out.
 * All public callbacks fire on the main thread.
 */
@SuppressLint("MissingPermission")
class BleControlServer(context: Context) : ControlChannel {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Advertising is deferred until the GATT service is actually registered (onServiceAdded). Held
    // here so the callback (or the safety-net timer) can start it. advertisingStarted makes the start
    // idempotent across whichever fires first.
    private var pendingAdvertiser: BluetoothLeAdvertiser? = null
    private var pendingServiceUuid: UUID? = null
    @Volatile private var advertisingStarted = false
    private val advFallbackToken = Any()

    /** The single owning central, or null. */
    @Volatile var clientDevice: BluetoothDevice? = null
        private set

    /** Guards pin/release of [clientDevice] (binder-thread connects vs main-thread releases). */
    private val pinLock = Any()
    /** Set by [markAuthenticated] once the owner's HELLO verified; ownership is final from then on. */
    @Volatile private var authenticated = false
    private val preAuthToken = Any()

    var onClientConnected: ((BluetoothDevice) -> Unit)? = null
    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null

    /** Fed only from the main thread (see [onCharacteristicWriteRequest]), as its contract requires. */
    private var reassembler = newReassembler()

    private fun newReassembler() = GattReassembler(
        onDropped = { why -> TransportDiag.log("TX: control chunk dropped — $why") }
    ) { frame -> onMessage?.invoke(frame) }

    /**
     * Inbound-chunk counter. "HELLO never completed" used to read the same whether the central's
     * writes never reached us or arrived and failed reassembly; the first-chunk line separates them.
     */
    private var chunksIn = 0

    private val sendQueue = ArrayDeque<ByteArray>()
    @Volatile private var notifyInFlight = false
    @Volatile private var notificationsEnabled = false
    @Volatile private var mtu = 23
    @Volatile private var closed = false

    /**
     * Consecutive congestion re-posts for the current chunk. Bounds the retry so a link that never
     * drains does not spin the main thread forever; reset once a chunk is accepted. Touched only on
     * the main thread.
     */
    private var notifyRetries = 0
    private val NOTIFY_RETRY_TOKEN = Any()

    /**
     * The chunk currently handed to the stack. Kept so a failed notification can be put back at the
     * head of the queue instead of leaving a hole in the frame. Main thread only.
     */
    private var inFlightChunk: ByteArray? = null

    companion object {
        private const val TAG = "OffGridBle"
        /** Safety net: if onServiceAdded never fires (rare OEM), advertise anyway after this delay. */
        private const val SERVICE_ADD_FALLBACK_MS = 1_500L
        /** Back-off between congestion re-posts, and the cap on how many times to retry one chunk. */
        private const val NOTIFY_RETRY_DELAY_MS = 30L
        private const val MAX_NOTIFY_RETRIES = 200
        /**
         * How long a central may hold the slot without a verified HELLO. The receiver re-sends
         * HELLO_RX for up to 5 s before it degrades, so 10 s never cuts off a slow but real one.
         */
        private const val PRE_AUTH_TIMEOUT_MS = 10_000L
        /** Hardware peripheral-mode availability — checked BEFORE building the payload `cap` bit. */
        fun canAdvertise(context: Context): Boolean {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter ?: return false
            return try {
                adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
            } catch (_: Exception) {
                false
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.i(TAG, "advertise failed code=$errorCode")
            mainHandler.post { closeOnce() }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (closed) return
            // The service is only live in the controller stack once this fires. Starting to advertise
            // BEFORE it does is a race: a fast-connecting central (notably MIUI) connects, runs service
            // discovery, finds nothing, and the link collapses with an immediate GATT 133 → the receiver
            // sees "device matched but control session FAILED". Advertise only now.
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { startAdvertisingNow() }
            } else {
                Log.i(TAG, "addService failed status=$status")
                mainHandler.post { closeOnce() }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (closed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val pinned = synchronized(pinLock) {
                        val current = clientDevice
                        if (current == null) { clientDevice = device; true }
                        else if (current.address != device.address) false
                        else return // same owner reconnecting at the stack level
                    }
                    if (pinned) {
                        mainHandler.post { onClientConnected?.invoke(device) }
                        mainHandler.postDelayed({
                            releasePinned(device, "no verified HELLO within ${PRE_AUTH_TIMEOUT_MS / 1000}s")
                        }, preAuthToken, PRE_AUTH_TIMEOUT_MS)
                    } else {
                        try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (clientDevice?.address == device.address) {
                        // Before the HELLO verified, a drop just frees the slot; after, the session is gone.
                        mainHandler.post {
                            if (authenticated) closeOnce() else releasePinned(device, "central disconnected before HELLO")
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            if (clientDevice?.address == device.address) mtu = newMtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            // A QUEUED (long) write is not a chunk. The stack splits one oversized value into
            // fragments at increasing offsets and only applies them on onExecuteWrite, so byte 0 of
            // a fragment is frame data, not the chunk index — feeding it to the reassembler poisons
            // the frame and the whole HELLO vanishes with nothing in the trace. We don't support the
            // long-write path (chunking exists precisely so we never need it), so refuse it loudly:
            // the central's write then FAILS, which its queue treats as a re-sendable chunk instead
            // of a silent success. Same for a non-zero offset on a normal write.
            val unsupported = preparedWrite || offset != 0
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device, requestId,
                        if (unsupported) BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED else BluetoothGatt.GATT_SUCCESS,
                        offset, null
                    )
                } catch (_: Exception) {}
            }
            if (closed || characteristic.uuid != ControlProtocol.CHAR_RX_UUID) return
            if (clientDevice?.address != device.address) return
            if (unsupported) {
                TransportDiag.log("TX: refused a queued/offset GATT write (prepared=$preparedWrite offset=$offset)")
                return
            }
            // Copy and hop to main: reassembly is single-threaded by contract and onMessage is
            // delivered there. mainHandler.post preserves FIFO and the ATT writes already arrive in
            // order, so chunk order survives.
            val chunk = value?.copyOf() ?: return
            mainHandler.post {
                if (closed) return@post
                if (chunksIn++ == 0) TransportDiag.log("TX: first control chunk in (${chunk.size} B)")
                reassembler.accept(chunk)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            var justEnabled = false
            if (descriptor.uuid == ControlProtocol.CCCD_UUID) {
                notificationsEnabled =
                    value contentEquals BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                justEnabled = notificationsEnabled
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                } catch (_: Exception) {}
            }
            // pumpQueue bails out while notifications are off, so anything queued before the central
            // subscribed would sit there forever without this nudge.
            if (justEnabled) mainHandler.post { pumpQueue() }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // Binder thread → main, so the congestion retry (which runs on main) and this are never
            // two writers racing for the head of the queue.
            mainHandler.post {
                if (closed) return@post
                notifyInFlight = false
                val chunk = inFlightChunk
                inFlightChunk = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // The chunk did not reach the central. Advancing over it leaves a hole in the
                    // frame, which reassembly now detects and discards — so the whole HELLO_ACK
                    // would be lost. Put it back instead; a retransmit is deduplicated by index.
                    Log.i(TAG, "notify failed status=$status — re-queuing the chunk")
                    chunk?.let { synchronized(sendQueue) { sendQueue.addFirst(it) } }
                    scheduleCongestionRetry()
                    return@post
                }
                notifyRetries = 0
                pumpQueue()
            }
        }
    }

    /**
     * Brings the GATT service + advertisement up. Returns false when this
     * device cannot act as a BLE peripheral (no adapter / BT off / no
     * peripheral mode / permission missing) — the caller then runs in
     * degraded no-control-channel mode, i.e. exactly the pre-V4 behavior.
     */
    fun start(serviceUuid: UUID): Boolean {
        val adapter = bluetoothManager?.adapter ?: return false
        try {
            if (!adapter.isEnabled) return false
            val adv = adapter.bluetoothLeAdvertiser ?: return false

            val server = bluetoothManager.openGattServer(appContext, serverCallback) ?: return false
            gattServer = server

            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rx = BluetoothGattCharacteristic(
                ControlProtocol.CHAR_RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val tx = BluetoothGattCharacteristic(
                ControlProtocol.CHAR_TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            tx.addDescriptor(
                BluetoothGattDescriptor(
                    ControlProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            service.addCharacteristic(rx)
            service.addCharacteristic(tx)
            txCharacteristic = tx
            if (!server.addService(service)) return false

            // Defer startAdvertising to onServiceAdded so the service is registered before any central
            // can connect (the MIUI GATT-133 race). A safety-net timer covers OEMs that never fire it.
            pendingAdvertiser = adv
            pendingServiceUuid = serviceUuid
            closed = false
            mainHandler.postDelayed({ startAdvertisingNow() }, advFallbackToken, SERVICE_ADD_FALLBACK_MS)
            return true
        } catch (e: SecurityException) {
            Log.i(TAG, "BLE server start blocked: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.i(TAG, "BLE server start failed: ${e.message}")
            return false
        }
    }

    /** Begins the deferred advertisement exactly once (called by onServiceAdded or the fallback timer). */
    private fun startAdvertisingNow() {
        if (closed || advertisingStarted) return
        val adv = pendingAdvertiser ?: return
        val uuid = pendingServiceUuid ?: return
        advertisingStarted = true
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(uuid))
                .build()
            adv.startAdvertising(settings, data, advertiseCallback)
            advertiser = adv
        } catch (e: Exception) {
            advertisingStarted = false
            Log.i(TAG, "advertise start failed: ${e.message}")
            mainHandler.post { closeOnce() }
        }
    }

    override fun send(frame: ByteArray) {
        if (closed) return
        synchronized(sendQueue) {
            GattChunker.split(frame, mtu).forEach { sendQueue.add(it) }
        }
        // [ControlChannel] accepts send from any thread; the pump stays main-thread-confined so
        // there is exactly one writer touching txCharacteristic and the queue's head.
        mainHandler.post { pumpQueue() }
    }

    /** Dispatches one chunk. Main thread only. */
    @Suppress("DEPRECATION")
    private fun pumpQueue() {
        if (closed || notifyInFlight || !notificationsEnabled) return
        val device = clientDevice ?: return
        val tx = txCharacteristic ?: return
        val chunk = synchronized(sendQueue) { sendQueue.poll() } ?: return
        notifyInFlight = true
        inFlightChunk = chunk
        try {
            tx.value = chunk
            // notifyCharacteristicChanged returns false on GATT congestion; in that
            // case onNotificationSent never fires, so we must clear notifyInFlight,
            // re-queue the unsent chunk at the front, and re-post pumpQueue ourselves
            // — otherwise the send queue stalls forever.
            val ok = gattServer?.notifyCharacteristicChanged(device, tx, false) == true
            if (!ok) {
                // notifyRetries is reset by a SUCCESSFUL onNotificationSent, not here: a chunk the
                // stack accepted but never delivered must still count against the retry cap.
                notifyInFlight = false
                inFlightChunk = null
                synchronized(sendQueue) { sendQueue.addFirst(chunk) }
                scheduleCongestionRetry()
            }
        } catch (_: Exception) {
            notifyInFlight = false
            inFlightChunk = null
            // The chunk was already polled off the queue; put it back so the
            // retry below does not silently drop it.
            synchronized(sendQueue) { sendQueue.addFirst(chunk) }
            scheduleCongestionRetry()
        }
    }

    /**
     * Re-posts [pumpQueue] after a short back-off instead of immediately, so persistent GATT
     * congestion can't busy-loop the main thread. Bounded by [MAX_NOTIFY_RETRIES]; once exhausted the
     * link is treated as dead and the session is closed.
     */
    private fun scheduleCongestionRetry() {
        if (closed) return
        if (notifyRetries >= MAX_NOTIFY_RETRIES) {
            Log.i(TAG, "notify congested past cap, closing")
            closeOnce()
            return
        }
        notifyRetries++
        mainHandler.postDelayed({ pumpQueue() }, NOTIFY_RETRY_TOKEN, NOTIFY_RETRY_DELAY_MS)
    }

    /** The owner's HELLO verified: keep this central for the rest of the session. Main thread. */
    fun markAuthenticated() {
        authenticated = true
        mainHandler.removeCallbacksAndMessages(preAuthToken)
    }

    /** The owner failed the HELLO: drop it and accept the next central. No-op once authenticated. Main thread. */
    fun releaseClient(why: String) = releasePinned(null, why)

    /** Frees the slot if it is still held by [only] (or by anyone, when null) and not yet authenticated. */
    private fun releasePinned(only: BluetoothDevice?, why: String) {
        if (closed || authenticated) return
        val dropped = synchronized(pinLock) {
            val current = clientDevice ?: return
            if (only != null && current.address != only.address) return
            clientDevice = null
            current
        }
        mainHandler.removeCallbacksAndMessages(preAuthToken)
        mainHandler.removeCallbacksAndMessages(NOTIFY_RETRY_TOKEN)
        TransportDiag.log("TX: BLE control slot released ($why) — next central may connect")
        try { gattServer?.cancelConnection(dropped) } catch (_: Exception) {}
        // Nothing from the old central may leak into the next one's session.
        reassembler = newReassembler()
        chunksIn = 0
        notificationsEnabled = false
        mtu = 23
        notifyInFlight = false
        inFlightChunk = null
        notifyRetries = 0
        synchronized(sendQueue) { sendQueue.clear() }
    }

    private fun closeOnce() {
        if (closed) return
        close()
        onClosed?.invoke()
    }

    override fun close() {
        closed = true
        mainHandler.removeCallbacksAndMessages(advFallbackToken)
        mainHandler.removeCallbacksAndMessages(NOTIFY_RETRY_TOKEN)
        mainHandler.removeCallbacksAndMessages(preAuthToken)
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertiser = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        clientDevice = null
        notifyInFlight = false
        inFlightChunk = null
        notifyRetries = 0
        synchronized(sendQueue) { sendQueue.clear() }
    }
}
