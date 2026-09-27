package eu.kanade.tachiyomi.data.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Pure QR decoding built on ZXing (no Google Play Services).
 *
 * Split out from the CameraX UI so the pixel-handling paths — which are the
 * error-prone part — are unit-testable without a camera.
 */
internal object SyncQrDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * Decodes a QR code from an RGBA_8888 buffer (CameraX
     * `OUTPUT_IMAGE_FORMAT_RGBA_8888`), honouring row/pixel strides.
     */
    fun decodeRgba(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): String? {
        if (width <= 0 || height <= 0 || pixelStride < 3) return null
        val pixels = IntArray(width * height)
        var offset = 0
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val i = rowStart + x * pixelStride
                if (i + 2 >= data.size) return null
                val r = data[i].toInt() and 0xFF
                val g = data[i + 1].toInt() and 0xFF
                val b = data[i + 2].toInt() and 0xFF
                pixels[offset++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return decode(RGBLuminanceSource(width, height, pixels))
    }

    /**
     * Decodes a QR code from a grayscale buffer (the Y plane of YUV_420_888,
     * whose row stride may include padding).
     */
    fun decodeLuminance(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        if (width <= 0 || height <= 0) return null
        val source = if (rowStride == width) {
            PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        } else {
            // Repack to a tightly-packed buffer so ZXing's stride assumption holds.
            val packed = ByteArray(width * height)
            for (y in 0 until height) {
                val rowStart = y * rowStride
                if (rowStart + width > data.size) return null
                data.copyInto(packed, y * width, rowStart, rowStart + width)
            }
            PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false)
        }
        return decode(source)
    }

    private fun decode(source: com.google.zxing.LuminanceSource): String? {
        val reader = MultiFormatReader()
        return try {
            reader.setHints(hints)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
