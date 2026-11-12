package pl.umamusume.tiket.core.validation

enum class ErrorCode {
    INVALID_SELECTION, UNKNOWN_HORSE, SCRATCHED_HORSE, DUPLICATE_HORSE, EMPTY_EXPANSION,
    EXPANSION_LIMIT, INVALID_UNITS, INVALID_STAKE, INVALID_TAKE_RATE, ARITHMETIC_OVERFLOW,
    INSUFFICIENT_BALANCE, PLAYER_NOT_ENROLLED, DAY_NOT_RUNNING, BETTING_CLOSED, QUOTE_CHANGED,
    INVALID_TRANSITION, RESULT_NOT_VERIFIED, INVALID_RESULT, UNSUPPORTED_RESULT,
    INCONSISTENT_POOL, ALREADY_REFUNDED, ALREADY_SETTLED, INVALID_DEFINITION,
    DUPLICATE_OPERATION, INCONSISTENT_ACCOUNT, WITHDRAWAL_PROHIBITED
}
data class DomainError(val code: ErrorCode, val detail: String)
class DomainException(val error: DomainError) : IllegalArgumentException(error.toString())
/** Explicit policy outcome. Rejected decisions carry a stable error code; adapters choose transport statuses. */
sealed interface Decision<out T> {
    data class Accepted<T>(val value: T) : Decision<T>
    data class Rejected(val error: DomainError) : Decision<Nothing>
}
fun reject(code: ErrorCode, detail: String): Nothing = throw DomainException(DomainError(code, detail))
fun ensure(condition: Boolean, code: ErrorCode, detail: String) { if (!condition) reject(code, detail) }
fun <T> decide(block: () -> T): Decision<T> = try { Decision.Accepted(block()) }
catch (e: DomainException) { Decision.Rejected(e.error) }
catch (e: ArithmeticException) { Decision.Rejected(DomainError(ErrorCode.ARITHMETIC_OVERFLOW, e.message ?: "numeric overflow")) }
fun <T> Decision<T>.getOrThrow(): T = when (this) {
    is Decision.Accepted -> value
    is Decision.Rejected -> throw DomainException(error)
}
internal fun <T> frozen(values: Collection<T>): List<T> = java.util.List.copyOf(values)
internal fun <K,V> frozenMap(values: Map<K,V>): Map<K,V> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
internal fun <T> frozenSet(values: Collection<T>): Set<T> = java.util.Collections.unmodifiableSet(LinkedHashSet(values))
