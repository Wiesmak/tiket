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
class IdempotencyRepository(private val jdbc: JdbcTemplate, private val tx: PersistenceTransactions) {
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
        if (inserted == 0) {
            val old = load(key) ?: throw IllegalStateException("Incomplete command in current transaction")
            if (old.fingerprint != fingerprint) throw IdempotencyConflictException()
            old.resultReference
        } else {
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
            result
        }
    }
}
