package com.cappielloantonio.tempo.ui.fragment

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The QR drawables are generated once by scripts/render-qr-drawable.sh and
 * checked in, so nothing at build time ties them to the URL they claim. This
 * scans each one as a camera would, white margin included.
 */
class QrDrawableTest {
    @Test
    fun `the credits QR opens the credits page`() {
        assertEquals(CREDITS_URL, scan("qr_credits"))
    }

    private fun scan(drawable: String): String {
        val xml = File("src/main/res/drawable/$drawable.xml").readText()
        val side = Regex("""android:viewportWidth="(\d+)"""").find(xml)!!.groupValues[1].toInt()
        val dark = Regex("""android:fillColor="#FF000000"\s+android:pathData="([^"]+)"""").find(xml)!!.groupValues[1]

        // The script writes only "MX,YhNv1h-Nz" runs, one module tall.
        val modules = Array(side) { BooleanArray(side) }
        Regex("""M(\d+),(\d+)h(\d+)v1h-\d+z""").findAll(dark).forEach { run ->
            val (x, y, n) = run.destructured
            for (i in 0 until n.toInt()) modules[y.toInt()][x.toInt() + i] = true
        }

        val scale = 4
        val width = side * scale
        val pixels =
            IntArray(width * width) { i ->
                if (modules[i / width / scale][i % width / scale]) BLACK else WHITE
            }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
