package com.EntropicSoftwareLab.ClipboardShare.transport.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import java.util.ArrayDeque
import java.util.UUID

/**
 * Receiver-side control endpoint (§4): hardware-filtered scan on the
 * token-derived service UUID (O(1) per advertisement, zero app wake-ups for
 * foreign devices), then GATT client with MTU 247 and TX-notify subscription.
 *
 * The matched [device] is retained — tier T4 dials its L2CAP PSM on the very
 * same BluetoothDevice, which is how the BT data tier never needs discovery.
 *
 * All public callbacks fire on the main thread.
 */
@SuppressLint("MissingPermission")
class BleControlClient(context: Context) : ControlChannel {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    /** The sender's device once matched — tier T4 connects to it directly. */
    @Volatile var device: BluetoothDevice? = null
        private set

    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var onReady: ((BluetoothDevice?) -> Unit)? = null

    /** Fed only from the main thread (see [onCharacteristicChanged]), as its contract requires. */
    private val reassembler = GattReassembler(
        onDropped = { why -> TransportDiag.log("RX: control chunk dropped — $why") }
    ) { frame -> onMessage?.invoke(frame) }

    /** Inbound-chunk counter, so the trace shows whether the sender's notifications arrive at all. */
    private var chunksIn = 0

    private val sendQueue = ArrayDeque<ByteArray>()
    @Volatile private var writeInFlight = false
    @Volatile private var mtu = 23
    @Volatile private var ready = false
    @Volatile private var closed = false

    /**
     * The chunk currently handed to the stack. Kept so a failed or abandoned write can put it back
     * at the head of the queue instead of leaving a hole in the frame. Main thread only.
     */
    private var inFlightChunk: ByteArray? = null
    /** Consecutive re-dispatch attempts for the current chunk; reset once one completes. Main thread only. */
    private var writeRetries = 0

    /**
     * GATT connect attempts so far (1 = first try). Bounds the retry below. Written only via
     * [attemptConnect] (always on the main thread); read from the binder-thread GATT callback, so it's
     * @Volatile for visibility.
     */
    @Volatile private var gattAttempts = 0
    private val GATT_RETRY_TOKEN = Any()

    companion object {
        private const val TAG = "OffGridBle"
        const val SCAN_BUDGET_MS = 6_000L

        /** Initial connect + one retry. MIUI frequently rejects the FIRST connect with a transient 133. */
        private const val MAX_GATT_ATTEMPTS = 2
        private const val GATT_RETRY_DELAY_MS = 350L

        /** Whole connect→MTU→discover→CCCD setup, per attempt. Some stacks connect and then
         *  go silent mid-chain (MTU quirk) — no callback ever fires, so without a deadline
         *  onReady never resolves and the ladder hangs on "connecting" (AND-10). */
        private const val SETUP_BUDGET_MS = 10_000L

        /**
         * How long to wait for a GATT write completion before assuming the callback is not coming.
         * Comfortably longer than a healthy connection interval round-trip, and far short of the 5s
         * HELLO_ACK budget the receiver is running against, so a wedged queue still has room to
         * drain several chunks before that deadline.
         */
        private const val WRITE_WATCHDOG_MS = 400L

        /**
         * Back-off before re-dispatching a chunk the stack refused (a write is still outstanding, or
         * the watchdog abandoned one). Re-posting with no delay busy-loops the main thread, which
         * starves the very looper that delivers the reassembled HELLO_ACK.
         */
        private const val WRITE_RETRY_DELAY_MS = 30L
        private const val MAX_WRITE_RETRIES = 100

        /** Central-mode availability — gates the whole control-channel attempt. */
        fun canScan(context: Context): Boolean {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter ?: return false
            return try {
                adapter.isEnabled && adapter.bluetoothLeScanner != null
            } catch (_: Exception) {
                false
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (closed || device != null) return
            device = result.device
            stopScan()
            mainHandler.removeCallbacksAndMessages(SCAN_TIMEOUT_TOKEN)
            // onScanResult fires on a binder thread; marshal the connect so gattAttempts and any
            // finishReady(null) on a synchronous connectGatt throw stay on the main thread.
            mainHandler.post { if (!closed) attemptConnect(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.i(TAG, "scan failed code=$errorCode")
            mainHandler.post { finishReady(null) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!g.requestMtu(247)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (ready) {
                        mainHandler.post { closeOnce() }
                    } else {
                        // The link dropped before the control channel was up. MIUI commonly rejects the
                        // FIRST connect with an immediate status 133/8 that's transient — retry once
                        // before degrading so a flaky first connect doesn't collapse the whole control
                        // channel (which desyncs the ladder into the degraded local path).
                        Log.i(TAG, "GATT connect failed status=$status (attempt $gattAttempts/$MAX_GATT_ATTEMPTS)")
                        val dev = device
                        if (dev != null && gattAttempts < MAX_GATT_ATTEMPTS) {
                            mainHandler.postDelayed({
                                // onReady == null means the setup watchdog (or another failure
                                // path) already reported — a late retry would leak its GATT.
                                if (closed || ready || onReady == null) return@postDelayed
                                try { gatt?.close() } catch (_: Exception) {}
                                gatt = null
                                attemptConnect(dev)
                            }, GATT_RETRY_TOKEN, GATT_RETRY_DELAY_MS)
                        } else {
                            mainHandler.post { finishReady(null) }
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (closed) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed) return
            val service = g.services.firstOrNull { svc ->
                svc.getCharacteristic(ControlProtocol.CHAR_RX_UUID) != null &&
                        svc.getCharacteristic(ControlProtocol.CHAR_TX_UUID) != null
            }
            val rx = service?.getCharacteristic(ControlProtocol.CHAR_RX_UUID)
            val tx = service?.getCharacteristic(ControlProtocol.CHAR_TX_UUID)
            val cccd = tx?.getDescriptor(ControlProtocol.CCCD_UUID)
            if (rx == null || tx == null || cccd == null) {
                mainHandler.post { finishReady(null) }
                return
            }
            rxCharacteristic = rx
            try {
                g.setCharacteristicNotification(tx, true)
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } catch (e: Exception) {
                Log.i(TAG, "notify subscribe failed: ${e.message}")
                mainHandler.post { finishReady(null) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed || descriptor.uuid != ControlProtocol.CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                mainHandler.post { finishReady(device) }
            } else {
                mainHandler.post { finishReady(null) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (closed || characteristic.uuid != ControlProtocol.CHAR_TX_UUID) return
            // COPY before leaving the callback: the value buffer belongs to the stack and is reused
            // for the next notification. Handing the live array to another thread would let a later
            // chunk overwrite this one in place.
            val chunk = characteristic.value?.copyOf() ?: return
            // Reassembly is single-threaded by contract, so run it where onMessage is delivered.
            // mainHandler.post preserves FIFO, and the ATT callbacks are already ordered, so chunk
            // order survives the hop.
            mainHandler.post {
                if (closed) return@post
                if (chunksIn++ == 0) TransportDiag.log("RX: first control chunk in (${chunk.size} B)")
                reassembler.accept(chunk)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // Marshal to the main thread: this fires on a binder thread, and the watchdog below runs
            // on main. Both used to drive pumpQueue, so two threads could pass the writeInFlight
            // check, both assign rxCharacteristic.value and both dispatch — losing or reordering a
            // chunk on a link that is supposed to be strictly ordered.
            mainHandler.post { onWriteComplete(status) }
        }
    }

    /**
     * Single owner of the write pump's completion path. Main thread only.
     *
     * A callback carries no correlation id, so when the watchdog has already abandoned a write there
     * is no way to tell a late completion from the replacement's. We deliberately do NOT try to
     * count them: the ATT stack itself serialises operations — a second [BluetoothGatt.writeCharacteristic]
     * while one is outstanding returns false, which [pumpQueue] handles by re-queuing and backing off.
     * So the worst a mis-attributed callback can do is dispatch the next chunk a little early and get
     * refused, whereas tracking abandonments needs state that never converges when a stack drops a
     * callback for good.
     */
    private fun onWriteComplete(status: Int) {
        if (closed) return
        mainHandler.removeCallbacksAndMessages(WRITE_WATCHDOG_TOKEN)
        writeInFlight = false
        val chunk = inFlightChunk
        inFlightChunk = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // The chunk did NOT reach the peer. Treating a failed write as delivered punches a hole
            // in the frame that reassembly cannot repair, so the peer discards the whole HELLO and
            // both sides wait out their timeouts. Put it back and try again.
            Log.i(TAG, "GATT write failed status=$status — re-queuing the chunk")
            chunk?.let { synchronized(sendQueue) { sendQueue.addFirst(it) } }
            scheduleWriteRetry()
            return
        }
        writeRetries = 0
        pumpQueue()
    }

    private val SCAN_TIMEOUT_TOKEN = Any()

    /**
     * Scans for the session UUID for [scanBudgetMs], then connects and
     * subscribes. [onReady] fires exactly once: with the sender's device on
     * success, or null (no match / BT off / link setup failed) — null means
     * "run the degraded local ladder".
     */
    fun start(serviceUuid: UUID, scanBudgetMs: Long = SCAN_BUDGET_MS, onReady: (BluetoothDevice?) -> Unit) {
        this.onReady = onReady
        val adapter = bluetoothManager?.adapter
        val scanner = try {
            adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        } catch (_: Exception) { null }
        if (scanner == null) {
            mainHandler.post { finishReady(null) }
            return
        }
        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            mainHandler.postDelayed({
                if (!closed && device == null) {
                    stopScan()
                    finishReady(null)
                }
            }, SCAN_TIMEOUT_TOKEN, scanBudgetMs)
        } catch (e: Exception) {
            Log.i(TAG, "scan start failed: ${e.message}")
            mainHandler.post { finishReady(null) }
        }
    }

    private val SETUP_TIMEOUT_TOKEN = Any()

    /** Guards the send queue against a write whose completion callback never arrives. */
    private val WRITE_WATCHDOG_TOKEN = Any()

    /** Back-off re-dispatch of a chunk the stack refused or the watchdog abandoned. */
    private val WRITE_RETRY_TOKEN = Any()

    /** Opens (or re-opens) the GATT client to [dev]; counts the attempt so the retry stays bounded. */
    private fun attemptConnect(dev: BluetoothDevice) {
        gattAttempts++
        // Setup watchdog (same pattern as the scan budget): if the chain goes silent between
        // CONNECTED and the CCCD write, report failure so the ladder degrades instead of hanging.
        mainHandler.removeCallbacksAndMessages(SETUP_TIMEOUT_TOKEN)
        mainHandler.postDelayed({
            if (!closed && !ready) {
                Log.i(TAG, "GATT setup timed out after ${SETUP_BUDGET_MS}ms (attempt $gattAttempts)")
                finishReady(null)
            }
        }, SETUP_TIMEOUT_TOKEN, SETUP_BUDGET_MS)
        try {
            gatt = dev.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.i(TAG, "connectGatt failed: ${e.message}")
            finishReady(null)
        }
    }

    private fun finishReady(d: BluetoothDevice?) {
        mainHandler.removeCallbacksAndMessages(SETUP_TIMEOUT_TOKEN)
        val cb = onReady ?: return
        onReady = null
        if (d == null && !ready) {
            // Setup failed — release any half-open GATT before reporting.
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
        }
        cb(d)
    }

    private fun stopScan() {
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    override fun send(frame: ByteArray) = send(frame, Int.MAX_VALUE)

    private fun send(frame: ByteArray, maxPayload: Int) {
        if (closed || !ready) return
        synchronized(sendQueue) {
            GattChunker.split(frame, mtu, maxPayload).forEach { sendQueue.add(it) }
        }
        // [ControlChannel] accepts send from any thread, but the pump is main-thread-confined so
        // there is exactly one writer touching rxCharacteristic and the queue's head.
        mainHandler.post { pumpQueue() }
    }

    /**
     * Discards anything still queued and re-sends [frame] chunked to the ATT minimum (20-byte
     * writes), for a handshake frame the peer never acknowledged.
     *
     * Two failure modes are covered at once, without having to tell them apart: a chunk lost
     * somewhere in the peer's stack (the retransmit simply replaces the frame — its first chunk
     * carries START, so the peer resyncs even if it is stuck mid-frame), and a peripheral that
     * negotiates a large MTU but silently swallows writes near the limit, which several OEM GATT
     * servers do. Dropping the queue first matters: leaving the abandoned attempt's chunks in front
     * of the new ones would interleave two index sequences and poison both.
     */
    fun resendSmall(frame: ByteArray) {
        if (closed || !ready) return
        mainHandler.post {
            if (closed || !ready) return@post
            mainHandler.removeCallbacksAndMessages(WRITE_WATCHDOG_TOKEN)
            mainHandler.removeCallbacksAndMessages(WRITE_RETRY_TOKEN)
            synchronized(sendQueue) { sendQueue.clear() }
            // The abandoned chunk is deliberately NOT re-queued: it belongs to the attempt we just
            // gave up on. A late completion callback for it only clears the flag and pumps.
            inFlightChunk = null
            writeInFlight = false
            writeRetries = 0
            send(frame, GattChunker.CONSERVATIVE_PAYLOAD)
        }
    }

    /** Dispatches one chunk. Main thread only — see [onWriteComplete]. */
    @Suppress("DEPRECATION")
    private fun pumpQueue() {
        if (closed || writeInFlight || !ready) return
        val g = gatt ?: return
        val rx = rxCharacteristic ?: return
        val chunk = synchronized(sendQueue) { sendQueue.poll() } ?: return
        writeInFlight = true
        inFlightChunk = chunk
        // WRITE_TYPE_DEFAULT (ATT Write Request), not NO_RESPONSE. A Write Command is
        // unacknowledged: a congested or slow peripheral drops it silently and nothing
        // retransmits, which on a handshake means the frame never arrives and both sides sit
        // there until their timeouts — observed as "receiver linked over BLE but HELLO never
        // completed" on the sender while the receiver reported HELLO_RX sent. A request is
        // acknowledged, retried by the link layer, and its completion callback is part of the
        // ATT flow rather than a best-effort courtesy. The server declares PROPERTY_WRITE and
        // answers with sendResponse when responseNeeded, so this is already supported. Control
        // frames are a few hundred bytes at most; throughput is irrelevant next to arriving.
        val dispatched = try {
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rx.value = chunk
            g.writeCharacteristic(rx)
        } catch (_: Exception) {
            false
        }
        if (!dispatched) {
            // Didn't reach the stack (usually because an ATT operation is still outstanding), so no
            // completion callback will ever fire. Put the chunk back and retry after a back-off —
            // re-posting immediately spins the main thread for as long as the link stays busy.
            writeInFlight = false
            inFlightChunk = null
            synchronized(sendQueue) { sendQueue.addFirst(chunk) }
            scheduleWriteRetry()
            return
        }
        // The pump advances only when onCharacteristicWrite fires. Several vendor stacks accept the
        // write, return true, and then never call back — one silent miss and the queue is wedged for
        // the life of the link, so every chunk after the first is lost and the peer waits on an END
        // that will never be sent. Re-queue the chunk and retry instead of skipping it: a duplicate
        // is now genuinely harmless because GattReassembler drops an exact retransmit by index,
        // whereas a skipped chunk would silently corrupt the frame.
        mainHandler.postDelayed({
            if (closed || !writeInFlight) return@postDelayed
            Log.i(TAG, "GATT write callback never arrived — re-queuing the chunk")
            writeInFlight = false
            inFlightChunk?.let { synchronized(sendQueue) { sendQueue.addFirst(it) } }
            inFlightChunk = null
            scheduleWriteRetry()
        }, WRITE_WATCHDOG_TOKEN, WRITE_WATCHDOG_MS)
    }

    /**
     * Re-runs [pumpQueue] after a back-off. Bounded: a link whose writes never land is dead, and
     * silently retrying forever would keep the ladder waiting on a control channel that is gone.
     */
    private fun scheduleWriteRetry() {
        if (closed) return
        if (writeRetries >= MAX_WRITE_RETRIES) {
            Log.i(TAG, "GATT writes stalled past cap — closing the control channel")
            closeOnce()
            return
        }
        writeRetries++
        mainHandler.postDelayed({ pumpQueue() }, WRITE_RETRY_TOKEN, WRITE_RETRY_DELAY_MS)
    }

    private fun closeOnce() {
        if (closed) return
        close()
        onClosed?.invoke()
    }

    override fun close() {
        closed = true
        mainHandler.removeCallbacksAndMessages(SCAN_TIMEOUT_TOKEN)
        mainHandler.removeCallbacksAndMessages(GATT_RETRY_TOKEN)
        mainHandler.removeCallbacksAndMessages(SETUP_TIMEOUT_TOKEN)
        mainHandler.removeCallbacksAndMessages(WRITE_WATCHDOG_TOKEN)
        mainHandler.removeCallbacksAndMessages(WRITE_RETRY_TOKEN)
        writeInFlight = false
        inFlightChunk = null
        writeRetries = 0
        stopScan()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        synchronized(sendQueue) { sendQueue.clear() }
    }
}
