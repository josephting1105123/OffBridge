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

    private val reassembler = GattReassembler { frame ->
        mainHandler.post { onMessage?.invoke(frame) }
    }

    private val sendQueue = ArrayDeque<ByteArray>()
    @Volatile private var writeInFlight = false
    @Volatile private var mtu = 23
    @Volatile private var ready = false
    @Volatile private var closed = false

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
                                if (closed || ready) return@postDelayed
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
            characteristic.value?.let { reassembler.accept(it) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            pumpQueue()
        }
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

    /** Opens (or re-opens) the GATT client to [dev]; counts the attempt so the retry stays bounded. */
    private fun attemptConnect(dev: BluetoothDevice) {
        gattAttempts++
        try {
            gatt = dev.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.i(TAG, "connectGatt failed: ${e.message}")
            finishReady(null)
        }
    }

    private fun finishReady(d: BluetoothDevice?) {
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

    @Suppress("DEPRECATION")
    override fun send(frame: ByteArray) {
        if (closed || !ready) return
        synchronized(sendQueue) {
            GattChunker.split(frame, mtu).forEach { sendQueue.add(it) }
        }
        pumpQueue()
    }

    @Suppress("DEPRECATION")
    private fun pumpQueue() {
        if (closed || writeInFlight || !ready) return
        val g = gatt ?: return
        val rx = rxCharacteristic ?: return
        val chunk = synchronized(sendQueue) { sendQueue.poll() } ?: return
        writeInFlight = true
        try {
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            rx.value = chunk
            if (!g.writeCharacteristic(rx)) {
                // Write didn't dispatch, so onCharacteristicWrite will never fire.
                // Re-queue the polled chunk at the head and re-pump so the queue
                // keeps draining instead of stalling the handshake (and losing the chunk).
                writeInFlight = false
                synchronized(sendQueue) { sendQueue.addFirst(chunk) }
                mainHandler.post { pumpQueue() }
            }
        } catch (_: Exception) {
            // Same recovery on throw: no callback will arrive to resume the pump.
            writeInFlight = false
            synchronized(sendQueue) { sendQueue.addFirst(chunk) }
            mainHandler.post { pumpQueue() }
        }
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
        stopScan()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        synchronized(sendQueue) { sendQueue.clear() }
    }
}
