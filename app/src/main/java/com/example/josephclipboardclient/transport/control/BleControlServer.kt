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
 * centrals are disconnected. All public callbacks fire on the main thread.
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

    var onClientConnected: ((BluetoothDevice) -> Unit)? = null
    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null

    private val reassembler = GattReassembler { frame ->
        mainHandler.post { onMessage?.invoke(frame) }
    }

    private val sendQueue = ArrayDeque<ByteArray>()
    @Volatile private var notifyInFlight = false
    @Volatile private var notificationsEnabled = false
    @Volatile private var mtu = 23
    @Volatile private var closed = false

    companion object {
        private const val TAG = "OffGridBle"
        /** Safety net: if onServiceAdded never fires (rare OEM), advertise anyway after this delay. */
        private const val SERVICE_ADD_FALLBACK_MS = 1_500L
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
                    val current = clientDevice
                    if (current == null) {
                        clientDevice = device
                        mainHandler.post { onClientConnected?.invoke(device) }
                    } else if (current.address != device.address) {
                        try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (clientDevice?.address == device.address) {
                        mainHandler.post { closeOnce() }
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
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                } catch (_: Exception) {}
            }
            if (closed || characteristic.uuid != ControlProtocol.CHAR_RX_UUID) return
            if (clientDevice?.address != device.address) return
            value?.let { reassembler.accept(it) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == ControlProtocol.CCCD_UUID) {
                notificationsEnabled =
                    value contentEquals BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                } catch (_: Exception) {}
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyInFlight = false
            pumpQueue()
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

    @Suppress("DEPRECATION")
    override fun send(frame: ByteArray) {
        if (closed) return
        synchronized(sendQueue) {
            GattChunker.split(frame, mtu).forEach { sendQueue.add(it) }
        }
        pumpQueue()
    }

    @Suppress("DEPRECATION")
    private fun pumpQueue() {
        if (closed || notifyInFlight || !notificationsEnabled) return
        val device = clientDevice ?: return
        val tx = txCharacteristic ?: return
        val chunk = synchronized(sendQueue) { sendQueue.poll() } ?: return
        notifyInFlight = true
        try {
            tx.value = chunk
            // notifyCharacteristicChanged returns false on GATT congestion; in that
            // case onNotificationSent never fires, so we must clear notifyInFlight,
            // re-queue the unsent chunk at the front, and re-post pumpQueue ourselves
            // — otherwise the send queue stalls forever.
            val ok = gattServer?.notifyCharacteristicChanged(device, tx, false) == true
            if (!ok) {
                notifyInFlight = false
                synchronized(sendQueue) { sendQueue.addFirst(chunk) }
                mainHandler.post { pumpQueue() }
            }
        } catch (_: Exception) {
            notifyInFlight = false
            // The chunk was already polled off the queue; put it back so the
            // retry below does not silently drop it.
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
        mainHandler.removeCallbacksAndMessages(advFallbackToken)
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertiser = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        clientDevice = null
        synchronized(sendQueue) { sendQueue.clear() }
    }
}
