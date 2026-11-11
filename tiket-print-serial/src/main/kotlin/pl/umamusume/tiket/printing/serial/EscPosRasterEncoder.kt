package pl.umamusume.tiket.printing.serial

import com.github.anastaciocintra.escpos.EscPos
import com.github.anastaciocintra.escpos.image.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.FilterOutputStream
import java.io.OutputStream

class EscPosRasterEncoder {
    fun write(image: BufferedImage, output: OutputStream, profile: SerialPrinterConfiguration) {
        require(image.width == 384 && image.type == BufferedImage.TYPE_BYTE_BINARY && image.height <= profile.maxHeight)
        // Session owns the physical stream; EscPos only owns this non-closing view.
        val view = object : FilterOutputStream(output) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }
        EscPos(view).use { escpos ->
            profile.density?.let { output.write(byteArrayOf(0x1d, 0x28, 0x4b, 2, 0, 0x31, it.toByte())) }
            profile.speed?.let { output.write(byteArrayOf(0x1d, 0x28, 0x4b, 2, 0, 0x32, it.toByte())) }
            var y = 0
            while (y < image.height) {
                val h = minOf(profile.bandHeight, image.height - y)
                escpos.write(RasterBitImageWrapper(), EscPosImage(CoffeeImageImpl(image.getSubimage(0, y, 384, h)), BitonalThreshold()))
                y += h
            }
            // Feed via the supported raster path instead of ESC d, which can be
            // ignored by firmware or depend on a zero native line-spacing setting.
            // Keep this separate from the receipt's maxHeight limit.
            var feedRows = profile.feedLines * 24
            if (feedRows > 0) {
                val blank = BufferedImage(384, minOf(profile.bandHeight, feedRows), BufferedImage.TYPE_BYTE_BINARY)
                val g = blank.createGraphics()
                try {
                    g.color = Color.WHITE
                    g.fillRect(0, 0, blank.width, blank.height)
                } finally {
                    g.dispose()
                }
                while (feedRows > 0) {
                    val h = minOf(blank.height, feedRows)
                    escpos.write(RasterBitImageWrapper(), EscPosImage(CoffeeImageImpl(blank.getSubimage(0, 0, 384, h)), BitonalThreshold()))
                    feedRows -= h
                }
            }
        }
    }
}
