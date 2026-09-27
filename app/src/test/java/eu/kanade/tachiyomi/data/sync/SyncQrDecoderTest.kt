package eu.kanade.tachiyomi.data.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Exercises the real ZXing decode path against a QR generated in-process, so the
 * pixel/stride handling is covered without needing a camera.
 */
class SyncQrDecoderTest {

    private val payload = "https://sync.example.com|mhk_TESTKEY1234567890"

    /**
     * Builds a QR matrix scaled by [scale] with a [quietZone]-module white border.
     * A quiet zone is required for detection; camera frames provide it naturally.
     */
    private fun matrix(scale: Int = 4, quietZone: Int = 4): BitMatrix {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val base = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        val innerW = base.width + quietZone * 2
        val innerH = base.height + quietZone * 2
        val scaled = BitMatrix(innerW * scale, innerH * scale)
        for (y in 0 until innerH) {
            for (x in 0 until innerW) {
                val bx = x - quietZone
                val by = y - quietZone
                val dark = bx in 0 until base.width && by in 0 until base.height && base.get(bx, by)
                if (!dark) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        scaled.set(x * scale + dx, y * scale + dy)
                    }
                }
            }
        }
        return scaled
    }

    /** Renders the QR as tightly-packed RGBA_8888 bytes (black=0, white=255). */
    private fun rgbaBytes(m: BitMatrix): ByteArray {
        val out = ByteArray(m.width * m.height * 4)
        var i = 0
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                val v = if (m.get(x, y)) 0 else 255
                out[i++] = v.toByte()
                out[i++] = v.toByte()
                out[i++] = v.toByte()
                out[i++] = 0xFF.toByte()
            }
        }
        return out
    }

    /** Renders the QR as a tightly-packed grayscale buffer (the Y plane). */
    private fun luminanceBytes(m: BitMatrix): ByteArray {
        val out = ByteArray(m.width * m.height)
        var i = 0
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                out[i++] = if (m.get(x, y)) 0 else 255.toByte()
            }
        }
        return out
    }

    @Test
    fun `decodes rgba_8888 tightly packed`() {
        val m = matrix()
        val decoded = SyncQrDecoder.decodeRgba(rgbaBytes(m), m.width, m.height, m.width * 4, 4)
        decoded shouldBe payload
    }

    @Test
    fun `decodes rgba_8888 with row padding`() {
        val m = matrix()
        val pixelStride = 4
        // Simulate a row stride wider than the visible width.
        val rowStride = m.width * pixelStride + 12
        val data = ByteArray(rowStride * m.height)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                val v = if (m.get(x, y)) 0 else 255
                val i = y * rowStride + x * pixelStride
                data[i] = v.toByte()
                data[i + 1] = v.toByte()
                data[i + 2] = v.toByte()
                data[i + 3] = 0xFF.toByte()
            }
        }
        val decoded = SyncQrDecoder.decodeRgba(data, m.width, m.height, rowStride, pixelStride)
        decoded shouldBe payload
    }

    @Test
    fun `decodes grayscale luminance tightly packed`() {
        val m = matrix()
        SyncQrDecoder.decodeLuminance(luminanceBytes(m), m.width, m.height) shouldBe payload
    }

    @Test
    fun `decodes grayscale luminance with row padding`() {
        val m = matrix()
        val rowStride = m.width + 7
        val data = ByteArray(rowStride * m.height)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                data[y * rowStride + x] = if (m.get(x, y)) 0 else 255.toByte()
            }
        }
        SyncQrDecoder.decodeLuminance(data, m.width, m.height, rowStride) shouldBe payload
    }

    @Test
    fun `decodes a scaled up code`() {
        val m = matrix(scale = 6)
        SyncQrDecoder.decodeRgba(rgbaBytes(m), m.width, m.height, m.width * 4, 4) shouldBe payload
    }

    /**
     * CameraX buffers are delivered in sensor orientation, so the QR may arrive
     * rotated by 90/180/270 degrees. ZXing detects QR orientation itself.
     */
    @Test
    fun `decodes at every quarter turn`() {
        val m = matrix()
        val w = m.width
        val h = m.height
        val src = rgbaBytes(m)

        fun pixel(x: Int, y: Int, srcW: Int): IntArray {
            val i = (y * srcW + x) * 4
            return intArrayOf(
                src[i].toInt() and 0xFF,
                src[i + 1].toInt() and 0xFF,
                src[i + 2].toInt() and 0xFF,
            )
        }

        fun rgba(px: IntArray): ByteArray {
            val out = ByteArray(px.size)
            for (i in px.indices) out[i] = px[i].toByte()
            return out
        }

        // 180 degrees: same dimensions.
        val r180 = IntArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixel(w - 1 - x, h - 1 - y, w)
                val i = (y * w + x) * 4
                r180[i] = p[0]
                r180[i + 1] = p[1]
                r180[i + 2] = p[2]
                r180[i + 3] = 0xFF
            }
        }
        SyncQrDecoder.decodeRgba(rgba(r180), w, h, w * 4, 4) shouldBe payload

        // 90 degrees: dimensions swap (new width = h, new height = w).
        val r90 = IntArray(w * h * 4)
        for (y in 0 until w) {
            for (x in 0 until h) {
                // dest(x, y) = src(y, h - 1 - x)
                val p = pixel(y, h - 1 - x, w)
                val i = (y * h + x) * 4
                r90[i] = p[0]
                r90[i + 1] = p[1]
                r90[i + 2] = p[2]
                r90[i + 3] = 0xFF
            }
        }
        SyncQrDecoder.decodeRgba(rgba(r90), h, w, h * 4, 4) shouldBe payload
    }

    @Test
    fun `returns null for blank frames`() {
        val size = 64
        SyncQrDecoder.decodeRgba(ByteArray(size * size * 4) { 0xFF.toByte() }, size, size, size * 4, 4)
            .shouldBeNull()
        SyncQrDecoder.decodeLuminance(ByteArray(size * size) { 0xFF.toByte() }, size, size).shouldBeNull()
    }

    @Test
    fun `returns null for invalid dimensions or strides`() {
        SyncQrDecoder.decodeRgba(ByteArray(0), 0, 0, 0, 4).shouldBeNull()
        SyncQrDecoder.decodeLuminance(ByteArray(0), 0, 0).shouldBeNull()
        // pixelStride below 3 can't hold RGB.
        SyncQrDecoder.decodeRgba(ByteArray(16), 2, 2, 8, 2).shouldBeNull()
    }

    @Test
    fun `returns null when buffer is truncated`() {
        val m = matrix()
        val full = rgbaBytes(m)
        SyncQrDecoder.decodeRgba(full.copyOf(full.size / 4), m.width, m.height, m.width * 4, 4)
            .shouldBeNull()
    }
}
