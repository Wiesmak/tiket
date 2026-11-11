package pl.umamusume.tiket.printing.serial

import com.fazecast.jSerialComm.SerialPort
import pl.umamusume.tiket.printing.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Explicit, local-only diagnostics. Never loads or modifies purchased coupons/outbox records. */
object SerialSmoke {
    @JvmStatic fun main(args: Array<String>) {
        SerialPort.getCommPorts().forEach { println("${it.systemPortName}: ${it.descriptivePortName}") }
        require(args.size == 2 && args[0] == "--port") { "Supply --port COM9 (or an explicit Linux device)" }
        val profile = SerialPrinterConfiguration(args[1])
        val renderer = TicketRasterRenderer()
        val encoder = EscPosRasterEncoder()
        val provider = JSerialCommPortProvider()
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        val diagnostic = TicketPrintData(UUID.randomUUID(), "DIAGNOSTIC - NOT A PURCHASED TICKET",
            "001", 1, "LOCAL SMOKE", now, now, 1, "EXACTA", 2, 10, "#2,3,4", 6, 12, 120,
            "PLN", false, UUID.randomUUID())
        // Small diagnostic first, followed by a full representative fixture and reprint.
        provider.open(profile).use { session ->
            val image = java.awt.image.BufferedImage(384, 80, java.awt.image.BufferedImage.TYPE_BYTE_BINARY)
            val g = image.createGraphics()
            try {
                g.color = java.awt.Color.WHITE; g.fillRect(0,0,384,80)
                g.color = java.awt.Color.BLACK
                g.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 18)
                g.drawString("DIAGNOSTIC / 384 PIXELS", 12, 30)
                g.drawRect(0,0,383,79)
            } finally { g.dispose() }
            encoder.write(image, session.output, profile); session.output.flush()
        }
        val printer = SerialTicketPrinter(SerialRoutes(mapOf(1 to profile)), provider, renderer, encoder)
        for (data in listOf(diagnostic, diagnostic.copy(isReprint = true, printAttemptId = UUID.randomUUID()))) {
            val result = printer.print(data)
            println("Serial transmission: $result (paper output requires visual inspection)")
            check(result == PrintResult.Dispatched) { "Smoke transmission failed" }
        }
    }
}
