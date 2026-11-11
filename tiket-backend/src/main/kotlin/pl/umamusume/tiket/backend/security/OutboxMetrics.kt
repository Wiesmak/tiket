package pl.umamusume.tiket.backend.security

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class OutboxMetrics(private val jdbc: JdbcTemplate, registry: MeterRegistry) {
    private val counts = listOf("PENDING","CLAIMED","RETRYING","DISPATCHED","PERMANENTLY_FAILED").associateWith { state ->
        AtomicLong().also { count -> registry.gauge("tiket.print.jobs", listOf(io.micrometer.core.instrument.Tag.of("status", state)), count) }
    }
    private val available = AtomicLong().also { registry.gauge("tiket.print.metrics.available", it) }
    @Scheduled(fixedDelayString="\${tiket.printing.metrics-millis:10000}")
    fun refresh() {
        try {
            val rows=jdbc.queryForList("SELECT status,count(*) AS total FROM print_job GROUP BY status")
            counts.values.forEach { it.set(0) }
            rows.forEach { row -> counts[row["status"]]?.set((row["total"] as Number).toLong()) }
            available.set(1)
        } catch (_: org.springframework.dao.DataAccessException) { available.set(0) }
    }
}
