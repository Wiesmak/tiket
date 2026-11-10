package pl.umamusume.tiket.backend.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.printing.*
import pl.umamusume.tiket.printing.console.ConsoleTicketPrinter
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.*

data class ClaimedPrint(val job: UUID, val attempt: UUID, val owner: UUID, val startedAt: Instant)

@Component
class PrintOutbox(
    private val jdbc: JdbcTemplate, private val tx: PersistenceTransactions,
    private val coupons: CouponRepository, private val days: RaceDayRepository,
    private val commands: AdministrationCommands,
    @Value("\${tiket.printing.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${tiket.printing.lease-seconds:60}") private val leaseSeconds: Long
) {
    init {
        require(maxAttempts in 1..100 && leaseSeconds in 1..3600)
    }

    fun enqueue(coupon: UUID, reprint: Boolean): UUID {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO print_job(id,coupon_id,job_type) VALUES (?,?,?)",
            id,
            coupon,
            if (reprint) "REPRINT" else "ORIGINAL"
        )
        return id
    }

    fun status(r: PrintStatusQuery): PrintStatus = tx.required { status(uuid(r.printJobId)) }
    private fun status(id: UUID): PrintStatus {
        val builder = jdbc.one("SELECT * FROM print_job WHERE id=?", id) { rs ->
            PrintStatus.newBuilder().setPrintJobId(id.toString()).setCouponId(rs.uuid("coupon_id").toString())
                .setType(PrintJobType.valueOf(rs.getString("job_type")))
                .setStatus(DispatchStatus.valueOf(rs.getString("status")))
                .setAttemptCount(rs.getInt("attempt_count")).setLastError(rs.getString("last_error"))
        } ?: throw MissingRecord()
        jdbc.one(
            "SELECT * FROM print_attempt WHERE print_job_id=? ORDER BY started_at DESC,id DESC LIMIT 1",
            id
        ) { rs ->
            PrintAttemptInformation.newBuilder().setPrintAttemptId(rs.uuid("id").toString())
                .setStartedAt(rs.getTimestamp("started_at").toInstant().toString())
                .setCompletedAt(rs.getTimestamp("completed_at")?.toInstant()?.toString() ?: "")
                .setResult(rs.getString("result")).setError(rs.getString("error")).build()
        }?.let { builder.latestAttempt = it }
        return builder.build()
    }

    fun reprint(r: ReprintCouponRequest): PrintStatus {
        val day = RaceDayId(uuid(r.raceDayId));
        val coupon = CouponId(uuid(r.couponId))
        require(r.idempotencyKey.length in 1..256)
        return commands.execute(
            "ReprintCoupon",
            "${day.value}/${coupon.value}",
            r.idempotencyKey,
            r,
            PrintStatus::parseFrom
        ) {
            persisted { coupons.load(day, coupon) } ?: throw MissingRecord()
            status(enqueue(coupon.value, true))
        }
    }

    fun claim(): ClaimedPrint? = tx.required {
        val id = jdbc.one(
            """SELECT id FROM print_job WHERE
            (status IN ('PENDING','RETRYING') AND next_attempt_at<=clock_timestamp())
            OR (status='CLAIMED' AND lease_until<=clock_timestamp())
            ORDER BY enqueued_at,id FOR UPDATE SKIP LOCKED LIMIT 1"""
        ) { it.uuid("id") }
        if (id == null) return@required emptyList<ClaimedPrint>()
        // An expired attempt has an unknown dispatch outcome. Retry can duplicate output.
        jdbc.update(
            "UPDATE print_attempt SET completed_at=clock_timestamp(),result='LEASE_EXPIRED',error='Claim lease expired; dispatch outcome unknown' WHERE print_job_id=? AND result='STARTED'",
            id
        )
        val count = jdbc.queryForObject("SELECT attempt_count FROM print_job WHERE id=?", Int::class.java, id)!!
        if (count >= maxAttempts) {
            jdbc.update(
                "UPDATE print_job SET status='PERMANENTLY_FAILED',claim_owner=NULL,lease_until=NULL,last_error='Attempt limit reached after lease expiry' WHERE id=?",
                id
            )
            return@required emptyList<ClaimedPrint>()
        }
        val owner = UUID.randomUUID();
        val attempt = UUID.randomUUID()
        val start = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp::class.java)!!.toInstant()
        jdbc.update(
            "UPDATE print_job SET status='CLAIMED',claim_owner=?,lease_until=?,attempt_count=attempt_count+1 WHERE id=?",
            owner, timestamp(start.plusSeconds(leaseSeconds)), id
        )
        jdbc.update(
            "INSERT INTO print_attempt(id,print_job_id,started_at,result) VALUES (?,?,?,'STARTED')",
            attempt,
            id,
            timestamp(start)
        )
        listOf(ClaimedPrint(id, attempt, owner, start))
    }.singleOrNull()

    fun data(c: ClaimedPrint): TicketPrintData = tx.required {
        val row = jdbc.one(
            "SELECT c.race_day_id,j.coupon_id,j.job_type FROM print_job j JOIN coupon c ON c.id=j.coupon_id WHERE j.id=?",
            c.job
        ) { Triple(RaceDayId(it.uuid("race_day_id")), CouponId(it.uuid("coupon_id")), it.getString("job_type")) }
            ?: throw MissingRecord()
        val stored = persisted { coupons.load(row.first, row.second) } ?: throw MissingRecord()
        val coupon = stored.coupon
        val day = persisted { days.load(row.first) } ?: throw MissingRecord()
        val race = day.races.single { it.id == coupon.raceId }
        val numbers = race.runners.associate { it.horseId to it.programNumber }
        val expression = when (val s = coupon.selection) {
            is CouponSelection.Box -> "#" + s.horses.map { numbers.getValue(it) }
                .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }).joinToString(",")

            is CouponSelection.Positional -> s.positions.joinToString("/") {
                if (it is PositionChoice.Horse) numbers.getValue(
                    it.id
                ) else "*"
            }
        }
        TicketPrintData(
            coupon.id.value,
            race.racecourseName,
            "%03d".format(java.util.Locale.ROOT, day.displayNumber),
            stored.metadata.posNumber,
            stored.metadata.cashierId,
            LocalDateTime.ofInstant(coupon.purchasedAt, day.timeZone).truncatedTo(ChronoUnit.MINUTES),
            LocalDateTime.ofInstant(c.startedAt, day.timeZone).truncatedTo(ChronoUnit.MINUTES),
            race.number,
            coupon.betType.name,
            coupon.unitsPerCombination.value,
            coupon.unitStake.amount,
            expression,
            coupon.legs.size,
            coupon.legs.fold(0L) { n, l -> Math.addExact(n, l.units.value) },
            coupon.totalStake.amount,
            day.currency,
            row.third == "REPRINT",
            c.attempt
        )
    }

    fun complete(c: ClaimedPrint, result: PrintResult) = tx.required {
        val count = jdbc.one(
            "SELECT attempt_count FROM print_job WHERE id=? AND claim_owner=? AND status='CLAIMED' FOR UPDATE",
            c.job,
            c.owner
        ) { it.getInt(1) }
        if (count != null) {
            val success = result is PrintResult.Dispatched
            val retry = result is PrintResult.Failed && result.retryable && count < maxAttempts
            val error = if (result is PrintResult.Failed) result.message.take(1000) else ""
            val outcome = if (success) "DISPATCHED" else if (retry) "RETRYABLE_FAILURE" else "PERMANENT_FAILURE"
            jdbc.update(
                "UPDATE print_attempt SET completed_at=clock_timestamp(),result=?,error=? WHERE id=? AND result='STARTED'",
                outcome,
                error,
                c.attempt
            )
            val delay = minOf(300L, 1L shl minOf(count, 8))
            jdbc.update(
                """UPDATE print_job SET status=?,last_error=?,claim_owner=NULL,lease_until=NULL,
                next_attempt_at=clock_timestamp()+(? * interval '1 second') WHERE id=?""",
                if (success) "DISPATCHED" else if (retry) "RETRYING" else "PERMANENTLY_FAILED", error, delay, c.job
            )
        }
        Unit
    }
}

@Configuration
@EnableScheduling
class PrintingConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(TicketPrinter::class)
    fun ticketPrinter(): TicketPrinter = ConsoleTicketPrinter()
}

@Component
@ConditionalOnProperty(prefix = "tiket.printing", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PrintWorker(private val outbox: PrintOutbox, private val printer: TicketPrinter) {
    @Scheduled(fixedDelayString = "\${tiket.printing.poll-millis:1000}")
    fun poll() {
        repeat(10) { if (!dispatchOne()) return }
    }

    fun dispatchOne(): Boolean {
        check(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) { "Dispatch must run outside a transaction" }
        val claim = outbox.claim() ?: return false
        val result = try {
            printer.print(outbox.data(claim))
        } catch (e: InvalidPersistenceException) {
            PrintResult.Failed("Invalid persisted print data", false)
        } catch (e: Exception) {
            PrintResult.Failed("Printer dispatch failed: " + e.javaClass.simpleName, true)
        }
        outbox.complete(claim, result)
        return true
    }
}
