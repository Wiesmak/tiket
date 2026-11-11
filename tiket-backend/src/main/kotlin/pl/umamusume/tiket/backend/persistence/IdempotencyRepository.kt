package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

class IdempotencyConflictException : IllegalArgumentException("Idempotency key has different request contents")
data class CommandKey(val operation: String, val scope: String, val key: String) {
    init {
        require(operation.isNotBlank() && scope.isNotBlank() && key.isNotBlank())
    }
}

data class CompletedCommand(val fingerprint: String, val resultReference: String)

@Repository
class IdempotencyRepository(private val jdbc: JdbcTemplate, private val tx: PersistenceTransactions, private val metrics: io.micrometer.core.instrument.MeterRegistry) {
    fun load(key: CommandKey): CompletedCommand? = jdbc.one(
        "SELECT * FROM command_idempotency WHERE operation=? AND scope=? AND idempotency_key=? AND completed_at IS NOT NULL",
        key.operation,
        key.scope,
        key.key
    ) {
        CompletedCommand(it.getString("fingerprint"), it.getString("result_reference"))
    }

    /** Callback effects and completion commit together. Caller canonicalizes and fingerprints request. */
    fun execute(key: CommandKey, fingerprint: String, work: () -> String): String = tx.required {
        require(fingerprint.isNotBlank())
        val inserted = jdbc.update(
            "INSERT INTO command_idempotency(operation,scope,idempotency_key,fingerprint) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            key.operation,
            key.scope,
            key.key,
            fingerprint
        )
        val actor = pl.umamusume.tiket.backend.security.AuditIdentity.actor()
        if (inserted == 0 && actor != null) {
            val owner = jdbc.queryForList(
                "SELECT actor FROM command_actor WHERE operation=? AND scope=? AND idempotency_key=?",
                key.operation, key.scope, key.key
            ).singleOrNull()?.get("actor")?.toString()
            if (owner != null && owner != actor) throw io.grpc.Status.PERMISSION_DENIED.asRuntimeException()
        }
        fun audit(outcome: String) {
            if (actor != null) jdbc.update(
                "INSERT INTO operational_audit(id,actor,operation,resource_scope,correlation_id,request_digest,outcome) VALUES (?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID(), actor, key.operation, key.scope,
                pl.umamusume.tiket.backend.security.AuditIdentity.correlation.get() ?: java.util.UUID.randomUUID(),
                fingerprint, outcome
            )
        }
        if (inserted == 0) {
            val old = load(key) ?: throw IllegalStateException("Incomplete command in current transaction")
            if (old.fingerprint != fingerprint) {
                metrics.counter("tiket.idempotency", "outcome", "conflict").increment()
                throw IdempotencyConflictException()
            }
            metrics.counter("tiket.idempotency", "outcome", "replay").increment()
            audit("REPLAYED")
            old.resultReference
        } else {
            if (actor != null) jdbc.update(
                "INSERT INTO command_actor(operation,scope,idempotency_key,actor) VALUES (?,?,?,?)",
                key.operation, key.scope, key.key, actor
            )
            val result = work()
            require(result.isNotBlank())
            changed(
                jdbc.update(
                    "UPDATE command_idempotency SET result_reference=?,completed_at=clock_timestamp() WHERE operation=? AND scope=? AND idempotency_key=?",
                    result,
                    key.operation,
                    key.scope,
                    key.key
                )
            )
            audit("COMMITTED")
            result
        }
    }
}
