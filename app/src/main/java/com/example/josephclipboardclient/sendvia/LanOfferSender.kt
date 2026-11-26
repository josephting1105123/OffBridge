package com.EntropicSoftwareLab.ClipboardShare.sendvia

import android.content.Context
import android.util.Base64
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.DiscoveredDevice
import com.EntropicSoftwareLab.ClipboardShare.Ports
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.TransferHistoryManager
import com.EntropicSoftwareLab.ClipboardShare.TransferItem
import com.EntropicSoftwareLab.ClipboardShare.TransferRecord
import com.EntropicSoftwareLab.ClipboardShare.TransferSession
import com.EntropicSoftwareLab.ClipboardShare.TransferUtils
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SendDeviceUi
import java.net.Socket

/** Outcome of a LAN offer batch: whether any target failed and the first target's session ID. */
data class OfferBatchResult(val hasFailures: Boolean, val firstSessionId: String?)

object LanOfferSender {

    /**
     * First target of the offer batch currently in flight, or null when idle. Process-level
     * in-flight lock shared by every send screen rather than per-instance because the offer loop
     * runs in a detached scope that outlives the calling screen: a rotation or rebuild mid-send
     * would otherwise re-enable Send and let the user fire a duplicate OFFER at a device already
     * receiving one. Callers set and clear this field.
     */
    @Volatile
    var activeTargetIp: String? = null

    /** One queued offer of [sendOffers]: its session plus the record identifiers. */
    private data class PendingOffer(
        val sessionId: String,
        val targetIp: String,
        val targetName: String,
        val session: TransferSession,
    )

    /**
     * Sends LAN file offers sequentially to [ips]. Must be called off the main thread: blocks on
     * sockets and does not touch [activeTargetIp].
     */
    fun sendOffers(appContext: Context, items: List<TransferItem>, ips: List<String>): OfferBatchResult {
        val expandedItems = TransferItemExpander.expand(appContext, items)
        val manifestList = TransferItemExpander.buildManifest(appContext, expandedItems)
        val fileUriStrings = expandedItems.map { it.uri.toString() }

        // Register every target's session + pending record BEFORE any handshake so the history
        // detail Pro opens on completion already exists; the offers themselves keep running in
        // this detached scope even if the user leaves the screen first.
        val offers = ips.map { targetIp ->
            val sessionId = java.util.UUID.randomUUID().toString()
            val session = TransferSession(
                sessionId = sessionId,
                senderName = TransferDaemon.customDeviceName,
                totalFiles = expandedItems.size,
                manifest = manifestList,
                fileUris = fileUriStrings,
                targetIp = targetIp,
                isIncoming = false,
                isPaused = false
            )
            TransferDaemon.putActiveSession(sessionId, session)

            val targetName = TransferDaemon.discoveredNodes[targetIp]?.name ?: targetIp
            TransferHistoryManager.addRecord(appContext, TransferRecord(
                sessionId = sessionId,
                deviceName = targetName,
                direction = "outgoing",
                fileNames = manifestList.map { TransferUtils.manifestDisplayLeaf(it.first) },
                totalBytes = manifestList.sumOf { it.second },
                timestamp = System.currentTimeMillis(),
                status = "pending",
                filesCompleted = 0,
                totalFiles = expandedItems.size,
                fileSizes = manifestList.map { it.second },
                currentFileOffset = 0L,
                transport = "lan"
            ))
            PendingOffer(sessionId, targetIp, targetName, session)
        }

        var hasFailures = false
        for ((sessionId, targetIp, targetName, session) in offers) {

            try {
                // Bounded connect (mirrors DownloadEngine's resume path): the 2-arg Socket
                // ctor blocks on the OS default SYN timeout — a vanished peer stalled the
                // whole multi-device offer loop for tens of seconds (AND-24). connect runs
                // INSIDE use so a timeout still closes the socket instead of leaking the fd.
                Socket().use { fileSocket ->
                    fileSocket.connect(java.net.InetSocketAddress(targetIp, Ports.FILE_TRANSFER), 8000)
                    val outStream = fileSocket.getOutputStream()
                    val offerHeader = "OFFER|$sessionId|${TransferDaemon.customDeviceName}|${expandedItems.size}\n"
                    var manifestStr = "MANIFEST|"
                    for (manifestItem in manifestList) {
                        manifestStr += "${manifestItem.first}:${manifestItem.second}|"
                    }
                    manifestStr += "\n"

                    outStream.write(offerHeader.toByteArray(Charsets.UTF_8))
                    outStream.write(manifestStr.toByteArray(Charsets.UTF_8))
                    outStream.flush()

                    // Wait for the receiver's next line (shared size-capped reader — the old
                    // hand-rolled loop had no byte cap, AND-24).
                    fileSocket.soTimeout = 10000
                    val inStream = fileSocket.getInputStream()
                    var response = com.EntropicSoftwareLab.ClipboardShare.TransferUtils.readUntilNewline(inStream)

                    // AUTH2: a new receiver may challenge us to prove we hold the shared pairing key
                    // before it shows its incoming-file popup. Answer with HMAC-SHA256(pairingKey,
                    // nonce), then read the real OFFER_RECEIVED. An old receiver sends OFFER_RECEIVED
                    // directly and we fall straight through — so we tolerate BOTH sequences without
                    // depending on the peer's advertised caps.
                    if (response.startsWith("OFFER_CHALLENGE|")) {
                        val nonce = Base64.decode(response.removePrefix("OFFER_CHALLENGE|"), Base64.NO_WRAP)
                        val pairingKey = SecurityVault.getKeyForNode(appContext, targetIp)
                            ?: throw Exception("No pairing key to answer OFFER_CHALLENGE for $targetIp")
                        val proof = com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto.hmacSha256(pairingKey, nonce)
                        outStream.write("OFFER_PROOF|${android.util.Base64.encodeToString(proof, android.util.Base64.NO_WRAP)}\n".toByteArray(Charsets.UTF_8))
                        outStream.flush()
                        response = com.EntropicSoftwareLab.ClipboardShare.TransferUtils.readUntilNewline(inStream)
                    }

                    if (response != "OFFER_RECEIVED") {
                        throw Exception("Receiver did not acknowledge offer")
                    }

                    // Stop here. Receiver will connect back with ACCEPT_OFFER or DECLINE_OFFER.
                }

                // Keep session paused so the loop doesn't kill it and it waits for Accept
                session.isPaused = true

            } catch (e: Exception) {
                e.printStackTrace()
                // Surface the precise reason to logcat (Android's real diagnostics surface) — the
                // generic "interrupted" toast + "paused" history below otherwise hides WHY the send
                // failed. Key case for the phone->laptop "no popup" bug: "No pairing key to answer
                // OFFER_CHALLENGE for <ip>" means the phone can't answer the receiver's AUTH2
                // challenge (missing key for the targeted IP) vs a plain connect timeout.
                Log.w("OffBridgeXfer", "OFFER to $targetName ($targetIp) failed: ${e.message}", e)
                hasFailures = true
                // Keep the session alive and mark as paused so receiver can reconnect
                session.isPaused = true
                session.lastUpdated = System.currentTimeMillis()
                TransferHistoryManager.addRecord(appContext, TransferRecord(
                    sessionId = sessionId,
                    deviceName = targetName,
                    direction = "outgoing",
                    fileNames = manifestList.map { TransferUtils.manifestDisplayLeaf(it.first) },
                    totalBytes = manifestList.sumOf { it.second },
                    timestamp = System.currentTimeMillis(),
                    status = "paused",
                    filesCompleted = session.currentFileIndex,
                    totalFiles = expandedItems.size,
                    fileSizes = manifestList.map { it.second },
                    currentFileOffset = session.currentOffset,
                    transport = "lan"
                ))
            }
        }

        return OfferBatchResult(hasFailures, offers.firstOrNull()?.sessionId)
    }
}

fun DiscoveredDevice.toSendDeviceUi(context: Context): SendDeviceUi =
    SendDeviceUi(
        ip = ip,
        name = name,
        isWindows = os.equals("Windows", ignoreCase = true),
        isPaired = SecurityVault.getKeyForNode(context, ip) != null
    )
