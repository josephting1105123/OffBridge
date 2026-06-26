package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef

/**
 * NFC reader-mode payload extraction, factored out of QrScannerFragment so the
 * Receive screen's NFC toggle and the scanner can share one implementation.
 *
 * [onRawPayload] is invoked on the NFC binder thread — callers must post to the
 * main thread before touching UI.
 */
class NfcPayloadReader(
    private val onRawPayload: (String) -> Unit,
    /** Optional diagnostic sink (binder thread) — milestones of the read, for on-screen NFC debug. */
    private val onEvent: ((String) -> Unit)? = null
) : NfcAdapter.ReaderCallback {

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) { onEvent?.invoke("tag discovered: null"); return }
        val techs = tag.techList.joinToString(", ") { it.substringAfterLast('.') }
        onEvent?.invoke("tag discovered [$techs]")
        val payload = readNdefPayload(tag)
        if (payload != null) {
            onEvent?.invoke("payload OK (${payload.length} chars)")
            onRawPayload(payload)
        } else {
            onEvent?.invoke("read FAILED — no off-grid NDEF")
        }
    }

    private fun readNdefPayload(tag: Tag): String? {
        // Fast path: platform NDEF detection (skipped when reader runs with SKIP_NDEF_CHECK — Ndef.get
        // returns null and we go straight to the IsoDep Type-4 read below).
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                val message = ndef.ndefMessage
                ndef.close()
                if (message != null) {
                    onEvent?.invoke("Ndef tech read OK")
                    return extractMimePayload(message)
                }
            }
        } catch (_: Exception) {}
        // Fallback (and primary path under SKIP_NDEF_CHECK): raw Type 4 Tag read over IsoDep.
        return try { readNdefViaIsoDep(tag) } catch (e: Exception) {
            onEvent?.invoke("IsoDep read threw: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun extractMimePayload(message: NdefMessage): String? {
        for (record in message.records) {
            val mime = try { record.toMimeType() } catch (_: Exception) { null }
            if (mime == OffGridPayload.NDEF_MIME_TYPE) {
                return String(record.payload, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun readNdefViaIsoDep(tag: Tag): String? {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) { onEvent?.invoke("no IsoDep tech on tag"); return null }
        isoDep.connect()
        try {
            fun ok(resp: ByteArray) = resp.size >= 2 &&
                    resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()

            // SELECT NDEF application — PRIVATE AID, must match NdefHceService.NDEF_AID. The standard
            // NDEF AID is hijacked by the platform's empty-tag responder on the sender, so we use a
            // private AID ("F0" + "Clipbo") that routes only to our HCE.
            val selectAid = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
                0xF0.toByte(), 0x43, 0x6C, 0x69, 0x70, 0x62, 0x6F, 0x00
            )
            if (!ok(isoDep.transceive(selectAid))) {
                onEvent?.invoke("SELECT AID rejected (no HCE app on peer)")
                return null
            }
            onEvent?.invoke("SELECT AID ok")

            fun selectFile(fileId: Int): Boolean {
                val cmd = byteArrayOf(
                    0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02,
                    ((fileId shr 8) and 0xFF).toByte(), (fileId and 0xFF).toByte()
                )
                return ok(isoDep.transceive(cmd))
            }

            fun readBinary(offset: Int, length: Int): ByteArray? {
                val cmd = byteArrayOf(
                    0x00, 0xB0.toByte(),
                    ((offset shr 8) and 0xFF).toByte(), (offset and 0xFF).toByte(),
                    (length and 0xFF).toByte()
                )
                val resp = isoDep.transceive(cmd)
                return if (ok(resp)) resp.copyOfRange(0, resp.size - 2) else null
            }

            if (!selectFile(0xE103)) { onEvent?.invoke("SELECT CC file failed"); return null }
            readBinary(0, 15) ?: return null
            if (!selectFile(0xE104)) { onEvent?.invoke("SELECT NDEF file failed"); return null }
            val nlenBytes = readBinary(0, 2) ?: return null
            if (nlenBytes.size < 2) return null
            val nlen = ((nlenBytes[0].toInt() and 0xFF) shl 8) or (nlenBytes[1].toInt() and 0xFF)
            if (nlen <= 0) {
                // The peer's HCE answered but its NDEF file is empty — the "empty tag" symptom: the
                // sender hadn't staged a payload (group not yet up) when this tap happened.
                onEvent?.invoke("empty NDEF (NLEN=0) — sender not ready when tapped")
                return null
            }
            onEvent?.invoke("NDEF NLEN=$nlen, reading…")

            val data = java.io.ByteArrayOutputStream()
            var offset = 2
            while (data.size() < nlen) {
                val chunk = readBinary(offset, (nlen - data.size()).coerceAtMost(240)) ?: return null
                if (chunk.isEmpty()) return null
                data.write(chunk)
                offset += chunk.size
            }
            return extractMimePayload(NdefMessage(data.toByteArray()))
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    companion object {
        // SKIP_NDEF_CHECK: the peer is an HCE Type-4 "tag", not a physical NFC tag. Letting the
        // platform run its own NDEF pre-read before dispatch races our IsoDep APDU exchange and is a
        // known cause of flaky/empty HCE reads — we do the full Type-4 read ourselves in
        // [readNdefViaIsoDep], so suppress the platform check and dispatch the IsoDep tag straight to us.
        val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        /** Arms reader mode; returns false if the adapter is absent or disabled. */
        fun arm(activity: Activity, reader: NfcPayloadReader): Boolean {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
            if (!adapter.isEnabled) return false
            return try {
                adapter.enableReaderMode(activity, reader, READER_FLAGS, null)
                true
            } catch (_: Exception) {
                false
            }
        }

        /** Disarms reader mode; swallows exceptions. */
        fun disarm(activity: Activity) {
            try {
                NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
            } catch (_: Exception) {}
        }
    }
}
