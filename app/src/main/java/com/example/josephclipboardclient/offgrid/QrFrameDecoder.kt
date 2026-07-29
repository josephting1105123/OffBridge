package com.EntropicSoftwareLab.ClipboardShare.offgrid

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a QR code out of a CameraX analysis frame with ZXing.
 *
 * Replaces ML Kit's barcode scanner, which was the project's only closed-source dependency and
 * therefore disqualified it from SignPath Foundation signing. ZXing was already a dependency —
 * it generates the codes this decodes — so the swap adds nothing new to the tree.
 *
 * Two behavioural differences from ML Kit worth knowing at the call sites:
 *
 *  - [decode] is SYNCHRONOUS and runs on whatever thread calls it. ML Kit returned its result on
 *    the main thread via a listener; callers must now hop to the main thread themselves before
 *    touching UI or non-atomic state.
 *  - ZXing's detector is classical (binarize, find the three finder patterns) rather than trained,
 *    so it is less forgiving of blur, glare and steep angles. TRY_HARDER below buys some of that
 *    back at roughly double the CPU per frame.
 *
 * NOT thread-safe. [MultiFormatReader] carries state across a decode, so each camera session gets
 * its own instance; both call sites already run their analyzer on a dedicated single thread.
 */
class QrFrameDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                // QR only. Letting it consider every 1D format would multiply the per-frame cost
                // for formats this app has no use for.
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // Our codes are dense (a whole off-grid payload) and are read off another device's
                // screen, where glare and moire are normal rather than exceptional. The extra pass
                // is affordable because STRATEGY_KEEP_ONLY_LATEST simply drops frames we miss.
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
    }

    /**
     * The decoded text, or null if this frame has no readable QR — which is the overwhelmingly
     * common case while the user is still aiming. Never throws; a bad frame is not an error.
     *
     * Does not close [imageProxy]; the caller owns it and must close it in a finally.
     */
    fun decode(imageProxy: ImageProxy): String? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        // Restore the position: CameraX may hand the same backing buffer to a later frame, and a
        // consumed buffer would read as empty.
        buffer.rewind()

        // Y plane only. Luminance is all ZXing looks at, so skipping the chroma planes avoids a
        // full YUV->RGB conversion on every frame. rowStride, not width, is the real row pitch —
        // cameras pad rows to an alignment, and passing width here shears the image diagonally on
        // every device that does, which fails to decode in a way that looks like a dead scanner.
        val source = try {
            PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                imageProxy.height,
                0,
                0,
                imageProxy.width,
                imageProxy.height,
                false,
            )
        } catch (e: IllegalArgumentException) {
            // Crop outside the buffer: an unexpected stride/height combination. Skip the frame.
            return null
        }

        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            // ChecksumException / FormatException: a real code read badly. The next frame is
            // milliseconds away, so retrying beats surfacing anything to the user.
            null
        } finally {
            reader.reset()
        }
    }
}
