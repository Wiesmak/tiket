package pl.umamusume.tiket.printing.serial

import pl.umamusume.tiket.printing.*
import java.util.concurrent.locks.ReentrantLock

class SerialTicketPrinter(
    val routing: SerialRoutes,
    private val provider: SerialPortProvider = JSerialCommPortProvider(),
    private val renderer: TicketRasterRenderer = TicketRasterRenderer(),
    private val encoder: EscPosRasterEncoder = EscPosRasterEncoder()
) : TicketPrinter {
    private val locks = routing.routes.values.associate { it.portKey to ReentrantLock() }
    override fun print(data: TicketPrintData): PrintResult {
        val profile = routing.routes[data.pointOfSaleNumber]
            ?: return PrintResult.Failed("No serial route for POS", false)
        val lock = locks.getValue(profile.portKey)
        // Fail fast rather than hold an outbox lease while waiting behind a receipt.
        if (!lock.tryLock()) return PrintResult.Failed("Serial port busy; retry later", true)
        try {
            val image = try { renderer.render(data, profile.maxHeight) } catch (e: RuntimeException) {
                return PrintResult.Failed("Invalid receipt layout or safe length exceeded", false)
            }
            provider.open(profile).use { session ->
                encoder.write(image, session.output, profile)
                session.output.flush()
            }
            return PrintResult.Dispatched
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            return PrintResult.Failed("Serial transmission failed; outcome uncertain, retry may duplicate output", true)
        } catch (e: LinkageError) {
            return PrintResult.Failed("Serial native library unavailable", true)
        } finally { lock.unlock() }
    }
}
