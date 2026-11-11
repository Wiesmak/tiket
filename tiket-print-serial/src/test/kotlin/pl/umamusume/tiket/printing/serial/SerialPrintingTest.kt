package pl.umamusume.tiket.printing.serial

import org.junit.jupiter.api.Test
import pl.umamusume.tiket.printing.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SerialPrintingTest {
    private fun ticket(pos: Int = 1) = TicketPrintData(
        UUID.randomUUID(), "Racecourse", "007", pos, "cashier",
        LocalDateTime.of(2026, 11, 11, 12, 30), LocalDateTime.of(2026, 11, 11, 13, 45),
        3, "EXACTA", 2, 10, "#2,3,4", 6, 12, 120, "PLN", false, UUID.randomUUID()
    )
    private val profile = SerialPrinterConfiguration("COM9")
    private fun session(out: OutputStream, closed: AtomicInteger = AtomicInteger()) = object : SerialPortSession {
        override val output = out
        override fun close() { closed.incrementAndGet(); out.close() }
    }

    @Test fun allFieldsAndReprint() {
        val d = ticket()
        val renderer = TicketRasterRenderer()
        val content = renderer.content(d).joinToString("\n") { it.text }
        for (value in listOf(d.ticketId.toString(), d.racecourseName, "007", "POS 1", d.cashierIdentifier,
            "2026-11-11 12:30", "2026-11-11 13:45", "Race 3", "EXACTA", "Units/combination: 2",
            "Unit price: 10 PLN", "#2,3,4", "Combinations: 6", "Total units: 12", "TOTAL: 120 PLN",
            "ORIGINAL", d.printAttemptId.toString())) assertContains(content, value)
        assertFalse(content.contains("REPRINT"))
        assertTrue(renderer.content(d.copy(isReprint = true)).any { it.text == "REPRINT" })
    }

    @Test fun monochromeWhiteMarginsAndLongWrapping() {
        val renderer = TicketRasterRenderer()
        val normal = renderer.render(ticket())
        val long = renderer.render(ticket().copy(racecourseName = "Long racecourse ".repeat(12),
            cashierIdentifier = "x".repeat(100), betString = "#1,2,3,".repeat(30),
            unitBetPrice = Long.MAX_VALUE / 12, totalBetValue = (Long.MAX_VALUE / 12) * 12))
        assertEquals(384, long.width)
        assertEquals(BufferedImage.TYPE_BYTE_BINARY, long.type)
        assertTrue(long.height > normal.height)
        var black = 0
        for (y in 0 until long.height) for (x in 0 until 384) {
            val rgb = long.getRGB(x,y) and 0xffffff
            assertTrue(rgb == 0 || rgb == 0xffffff)
            if (x < 10 || x >= 374 || y < 8 || y >= long.height - 8) assertEquals(0xffffff, rgb)
            if (rgb == 0) black++
        }
        assertTrue(black > 1000)
    }

    @Test fun controlNormalizationAndSafeLimit() {
        val renderer = TicketRasterRenderer()
        val d = ticket()
        val a = renderer.render(d.copy(cashierIdentifier = "abc\n\u001b\u202edef"))
        val b = renderer.render(d.copy(cashierIdentifier = "abc   def"))
        assertContentEquals(a.getRGB(0,0,384,a.height,null,0,384), b.getRGB(0,0,384,b.height,null,0,384))
        assertFailsWith<IllegalArgumentException> { renderer.render(d.copy(betString = "x".repeat(40000))) }
        assertFailsWith<IllegalArgumentException> { renderer.render(d, 256) }
    }

    @Test fun rasterBandsExactlyReproduceRowsAndNoOtherCommands() {
        val image = BufferedImage(384, 777, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until image.height) for (x in 0 until 384)
            image.setRGB(x,y, if ((x * 13 + y * 7) % 19 < 8) Color.BLACK.rgb else Color.WHITE.rgb)
        val out = ByteArrayOutputStream()
        EscPosRasterEncoder().write(image, out, profile.copy(density = null, speed = null, feedLines = 0, bandHeight = 17))
        val bytes = out.toByteArray()
        var offset = 0
        var y = 0
        while (y < image.height) {
            val h = minOf(17, image.height - y)
            // Coffee wrapper emits left justification then GS v 0, normal mode.
            assertContentEquals(byteArrayOf(27,97,48,29,118,48,0,48,0,h.toByte(),0), bytes.copyOfRange(offset, offset+11))
            offset += 11
            repeat(h) { row ->
                repeat(48) { col ->
                    var expected = 0
                    repeat(8) { bit -> if (image.getRGB(col*8+bit,y+row) and 0xffffff == 0) expected = expected or (128 shr bit) }
                    assertEquals(expected, bytes[offset++].toInt() and 255)
                }
            }
            y += h
        }
        assertEquals(bytes.size, offset) // no native receipt text, feed or extra rows
    }

    @Test fun vendorCommandsAndFinalFeed() {
        val out = ByteArrayOutputStream()
        EscPosRasterEncoder().write(TicketRasterRenderer().render(ticket()), out, profile)
        val bytes = out.toByteArray()
        assertContentEquals(byteArrayOf(29,40,75,2,0,49,50,29,40,75,2,0,50,5), bytes.take(14).toByteArray())
        assertContentEquals(byteArrayOf(27,100,3), bytes.takeLast(3).toByteArray())
    }

    @Test fun invalidProfilesAndSharedPortConflicts() {
        for (bad in listOf("", " COM9", "COM9\n")) assertFailsWith<IllegalArgumentException> { SerialPrinterConfiguration(bad) }
        assertFailsWith<IllegalArgumentException> { profile.copy(density = 128) }
        assertFailsWith<IllegalArgumentException> { profile.copy(speed = 0) }
        assertFailsWith<IllegalArgumentException> { SerialRoutes(mapOf(0 to profile)) }
        assertFailsWith<IllegalArgumentException> { SerialRoutes(mapOf(1 to profile, 2 to profile.copy(port = "com9", baud = 19200))) }
        assertEquals(1, SerialRoutes(mapOf(1 to profile, 2 to profile.copy(port = "com9"))).lanes.size)
    }

    @Test fun selectedPortAndResourceOwnership() {
        val closed = AtomicInteger()
        val out = ByteArrayOutputStream()
        val printer = SerialTicketPrinter(SerialRoutes(mapOf(2 to profile)), SerialPortProvider {
            assertEquals("COM9", it.port); session(out, closed)
        })
        assertEquals(PrintResult.Dispatched, printer.print(ticket(2)))
        assertEquals(1, closed.get())
        assertTrue(out.size() > 1000)
        assertEquals(false, (printer.print(ticket()) as PrintResult.Failed).retryable)
    }

    @Test fun openAndNativeFailuresAreRetryable() {
        for (failure in listOf(IOException("secret"), UnsatisfiedLinkError("secret"))) {
            val printer = SerialTicketPrinter(SerialRoutes(mapOf(1 to profile)), SerialPortProvider { throw failure })
            val result = printer.print(ticket()) as PrintResult.Failed
            assertTrue(result.retryable); assertFalse(result.message.contains("secret"))
        }
    }

    @Test fun partialWriteClosesSessionAndAllowsRetry() {
        val closed = AtomicInteger()
        val attempts = AtomicInteger()
        val printer = SerialTicketPrinter(SerialRoutes(mapOf(1 to profile)), SerialPortProvider {
            session(object : OutputStream() {
                var bytes = 0
                override fun write(b: Int) { if (++bytes > 100 && attempts.get() == 0) throw IOException("disconnect") }
            }, closed)
        })
        val result = printer.print(ticket()) as PrintResult.Failed
        assertTrue(result.retryable); assertContains(result.message, "uncertain")
        attempts.incrementAndGet()
        assertEquals(PrintResult.Dispatched, printer.print(ticket()))
        assertEquals(2, closed.get())
    }

    @Test fun differentPortsReachWriteBarrierTogether() {
        val barrier = CyclicBarrier(2)
        val ports = ConcurrentHashMap.newKeySet<String>()
        val routes = SerialRoutes(mapOf(1 to profile, 2 to profile.copy(port = "/dev/ttyUSB0")))
        val printer = SerialTicketPrinter(routes, SerialPortProvider { config ->
            ports.add(config.port)
            session(object : OutputStream() {
                var started = false
                override fun write(b: Int) { if (!started) { started = true; barrier.await(10, TimeUnit.SECONDS) } }
            })
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { pos -> executor.submit<PrintResult> { printer.print(ticket(pos)) } }
            jobs.forEach { assertEquals(PrintResult.Dispatched, it.get(15,TimeUnit.SECONDS)) }
            assertEquals(setOf("COM9", "/dev/ttyUSB0"), ports)
        } finally { executor.shutdownNow() }
    }

    @Test fun samePortNeverOpensTwiceAndSubsequentJobSucceeds() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val printer = SerialTicketPrinter(SerialRoutes(mapOf(1 to profile, 2 to profile)), SerialPortProvider {
            opens.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(10,TimeUnit.SECONDS))
            session(ByteArrayOutputStream())
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<PrintResult> { printer.print(ticket()) }
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            assertTrue((printer.print(ticket(2)) as PrintResult.Failed).retryable)
            assertEquals(1, opens.get())
            release.countDown()
            assertEquals(PrintResult.Dispatched, first.get(10,TimeUnit.SECONDS))
            assertEquals(PrintResult.Dispatched, printer.print(ticket(2)))
            assertEquals(2, opens.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
