package com.example.josephclipboardclient.transport

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.data.db.IdentityKeyStore
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload
import com.EntropicSoftwareLab.ClipboardShare.P2pAdvertisingContext
import com.EntropicSoftwareLab.ClipboardShare.transport.SenderSessionController
import com.EntropicSoftwareLab.ClipboardShare.transport.NetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object OffGridAdvertiser {

    fun startAdvertising(
        context: Context,
        uiScope: CoroutineScope,
        ssid: String,
        psk: String,
        hostIp: String,
        goMac: String?,
        freq: Int?,
        manifest: List<Pair<String, Long>>,
        fileUris: List<String>,
        netKind: NetKind,
        softApBridge: Boolean,
        isLanOnly: Boolean,
        onPeerConnected: (String) -> Unit,
        onTransferComplete: (String) -> Unit,
        onTransferError: (Boolean) -> Unit,
        listener: SenderSessionController.Listener
    ): Pair<SenderSessionController, OffGridPayload> {
        // Ensure the file-transfer server is bound (idempotent)
        TransferDaemon.start(context.applicationContext)

        val ecdh = EcdhSession()
        val token = OffGridPayload.newToken()

        val controller = SenderSessionController(
            context, token, ecdh, ssid, psk, hostIp, goMac,
            listener,
            netKind = netKind
        )

        val lanIpValue = if (isLanOnly) {
            hostIp
        } else if (softApBridge) {
            Net.localLanIpv4() ?: hostIp
        } else {
            Net.localLanIpv4()
        }

        val payload = OffGridPayload.build(
            ssid = ssid,
            psk = psk,
            senderPublicKey = ecdh.getPublicKey(),
            token = token,
            senderName = TransferDaemon.customDeviceName,
            fileCount = manifest.size,
            totalBytes = manifest.sumOf { it.second },
            ip = hostIp,
            goDeviceAddress = goMac,
            frequencyMhz = freq,
            cap = controller.capabilityBits(),
            senderIdentityKey = IdentityKeyStore.publicKeyDer(context),
            lanIp = lanIpValue,
            softApBridge = softApBridge
        )

        TransferDaemon.setP2pContext(
            P2pAdvertisingContext(
                token = token,
                ecdh = ecdh,
                fileUris = fileUris,
                manifest = manifest,
                totalFiles = manifest.size,
                onPeerConnected = { deviceName -> uiScope.launch { onPeerConnected(deviceName) } },
                onTransferComplete = { sid -> uiScope.launch { onTransferComplete(sid) } },
                onTransferError = { paused -> uiScope.launch { onTransferError(paused) } }
            )
        )

        return Pair(controller, payload)
    }

    fun stopAdvertising() {
        TransferDaemon.setP2pContext(null)
    }
}
