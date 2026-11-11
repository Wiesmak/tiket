package pl.umamusume.tiket.printing.serial

import com.github.anastaciocintra.escpos.EscPos
import com.github.anastaciocintra.escpos.image.*
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
            // EscPos.feed also emits native font/style configuration. Send only feed control.
            if (profile.feedLines > 0) output.write(byteArrayOf(27, 100, profile.feedLines.toByte()))
        }
    }
}
