package pl.umamusume.tiket.backend.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.printing.*
import pl.umamusume.tiket.printing.serial.SerialRoutes

/** Additional lease check for serial completion; keep the legacy console API unchanged. */
class SerialLeaseCompletion(private val outbox: PrintOutbox, private val jdbc: JdbcTemplate,
    private val tx: PersistenceTransactions) {
    fun complete(claim: ClaimedPrint, result: PrintResult) = tx.required {
        val valid = jdbc.one(
            "SELECT id FROM print_job WHERE id=? AND claim_owner=? AND status='CLAIMED' AND lease_until>clock_timestamp() FOR UPDATE",
            claim.job, claim.owner) { it.uuid("id") }
        if (valid != null) outbox.complete(claim, result)
        Unit
    }
}

@Component
@ConditionalOnProperty(prefix = "tiket.printing", name = ["backend"], havingValue = "serial")
@ConditionalOnProperty(prefix = "tiket.printing", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SerialPrintWorker(private val outbox: PrintOutbox, private val printer: TicketPrinter,
    routes: SerialRoutes, jdbc: JdbcTemplate, tx: PersistenceTransactions) {
    private val lifecycle = Any()
    private var stopping = false
    private val completion = SerialLeaseCompletion(outbox, jdbc, tx)
    private val coordinator = SerialDispatchCoordinator(routes.lanes) { dispatchOne(it) }

    @Scheduled(fixedDelayString = "\${tiket.printing.poll-millis:1000}")
    fun poll() = coordinator.poll()

    fun dispatchOne(pos: Set<Int>): Boolean {
        check(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        val claim = synchronized(lifecycle) {
            if (stopping) return false
            outbox.claimForPos(pos)
        } ?: return false
        val result = try { printer.print(outbox.data(claim)) }
        catch (e: InvalidPersistenceException) { PrintResult.Failed("Invalid persisted print data", false) }
        catch (e: Exception) { PrintResult.Failed("Serial dispatch failed; outcome uncertain, retry may duplicate output", true) }
        catch (e: LinkageError) { PrintResult.Failed("Serial native library unavailable", true) }
        completion.complete(claim, result)
        return true
    }

    @EventListener(ContextClosedEvent::class)
    fun stopClaiming() {
        synchronized(lifecycle) { stopping = true }
        coordinator.close()
    }
}
