package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * NFC Forum Type 4 Tag emulation (HCE) serving the off-grid credential
 * payload as a single NDEF MIME record. HCE is the only NDEF-push path on
 * API 29+ (Android Beam was removed).
 *
 * Responds to SELECT AID (private F0436C6970626F), SELECT CC/NDEF file and
 * READ BINARY APDUs. The payload is staged by [QrDisplayFragment] via
 * [stagePayload]; while nothing is staged the service answers
 * "file not found" so a stray tap leaks nothing.
 */
class NdefHceService : HostApduService() {

    companion object {
        // NFC Forum Type 4 Tag NDEF application AID
        private const val FILE_ID_CC = 0xE103
        private const val FILE_ID_NDEF = 0xE104

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        private val SW_WRONG_PARAMS = byteArrayOf(0x6B, 0x00)

        // Private proprietary AID (F0-prefix = proprietary RID). The STANDARD NDEF Type-4 AID
        // (D2760000850101) is claimed by the platform's own empty-tag NDEF responder on many devices,
        // which hijacks the reader's SELECT AID and serves NLEN=0 — so our staged payload never reaches
        // the reader (observed: "SELECT AID ok" then "empty NDEF (NLEN=0)"). A private AID that only our
        // app registers routes unambiguously to THIS service. Our reader selects the same AID.
        // "F0" + "Clipbo" (0x43 0x6C 0x69 0x70 0x62 0x6F).
        private val NDEF_AID = byteArrayOf(
            0xF0.toByte(), 0x43, 0x6C, 0x69, 0x70, 0x62, 0x6F
        )

        /**
         * Capability Container: CCLEN=15, mapping v2.0, MLe/MLc=255,
         * NDEF file control TLV → file E104, max size 0x7FFF, read free,
         * write forbidden (read-only tag).
         */
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,             // CCLEN
            0x20,                   // Mapping version 2.0
            0x00, 0xFF.toByte(),    // MLe
            0x00, 0xFF.toByte(),    // MLc
            0x04, 0x06,             // NDEF File Control TLV
            0xE1.toByte(), 0x04,    // File ID E104
            0x7F, 0xFF.toByte(),    // Max NDEF file size
            0x00,                   // Read access: free
            0xFF.toByte()           // Write access: forbidden
        )

        @Volatile
        private var ndefFile: ByteArray? = null

        /** Arms the service with the credential payload (one NDEF MIME record). */
        fun stagePayload(payloadJson: String) {
            val record = NdefRecord.createMime(
                OffGridPayload.NDEF_MIME_TYPE,
                payloadJson.toByteArray(Charsets.UTF_8)
            )
            val message = NdefMessage(arrayOf(record)).toByteArray()
            val file = ByteArray(2 + message.size)
            file[0] = ((message.size shr 8) and 0xFF).toByte()
            file[1] = (message.size and 0xFF).toByte()
            System.arraycopy(message, 0, file, 2, message.size)
            ndefFile = file
        }

        /** Disarms the service; subsequent taps read nothing. */
        fun clearPayload() {
            ndefFile = null
        }
    }

    private var selectedFileId = -1

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        // Guard the whole handler: this runs on a binder callback, so any
        // arithmetic/index edge (offset math, copyOfRange) escaping here would
        // be swallowed by the framework and silently drop the NFC tap. Always
        // answer with a well-formed status word instead.
        return try {
            handleCommandApdu(commandApdu)
        } catch (e: Exception) {
            SW_INS_NOT_SUPPORTED
        }
    }

    private fun handleCommandApdu(commandApdu: ByteArray?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return SW_INS_NOT_SUPPORTED

        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        return when {
            // SELECT by AID
            cla == 0x00 && ins == 0xA4 && p1 == 0x04 -> {
                val lc = if (commandApdu.size > 4) commandApdu[4].toInt() and 0xFF else 0
                val aid = if (commandApdu.size >= 5 + lc) commandApdu.copyOfRange(5, 5 + lc) else ByteArray(0)
                if (aid.contentEquals(NDEF_AID)) {
                    // Confirms a reader (the receiver) coupled to THIS device's HCE and selected our
                    // app — surfaced on the sender's diagnostics panel. If this never appears on a
                    // tap, the radios aren't coupling (antenna alignment / NFC off / routing).
                    com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag.log(
                        if (ndefFile == null) "NFC: reader selected our app — but NO payload staged yet (empty tag)"
                        else "NFC: reader selected our app (payload ready, ${ndefFile?.size ?: 0} B)"
                    )
                    SW_OK
                } else SW_FILE_NOT_FOUND
            }

            // SELECT file by ID
            cla == 0x00 && ins == 0xA4 && p1 == 0x00 -> {
                if (commandApdu.size < 7) return SW_WRONG_PARAMS
                val fileId = ((commandApdu[5].toInt() and 0xFF) shl 8) or (commandApdu[6].toInt() and 0xFF)
                when (fileId) {
                    FILE_ID_CC, FILE_ID_NDEF -> {
                        selectedFileId = fileId
                        SW_OK
                    }
                    else -> SW_FILE_NOT_FOUND
                }
            }

            // READ BINARY
            cla == 0x00 && ins == 0xB0 -> {
                val offset = (p1 shl 8) or p2
                val le = if (commandApdu.size > 4) {
                    val raw = commandApdu[4].toInt() and 0xFF
                    if (raw == 0) 256 else raw
                } else 256

                val file = when (selectedFileId) {
                    FILE_ID_CC -> CC_FILE
                    FILE_ID_NDEF -> ndefFile ?: return SW_FILE_NOT_FOUND
                    else -> return SW_FILE_NOT_FOUND
                }
                // Reject offset >= file.size before copyOfRange so a malformed
                // READ BINARY can never index past the end of the file.
                if (offset >= file.size) return SW_WRONG_PARAMS
                val end = (offset + le).coerceAtMost(file.size)
                // The reader has pulled the last byte of the NDEF file → full payload delivered.
                if (selectedFileId == FILE_ID_NDEF && end >= file.size && file.size > 2) {
                    com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag.log(
                        "NFC: full payload served to receiver (${file.size} B)"
                    )
                }
                file.copyOfRange(offset, end) + SW_OK
            }

            else -> SW_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        selectedFileId = -1
    }
}
