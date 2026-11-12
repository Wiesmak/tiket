package pl.umamusume.tiket.printing

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*

/** Resolved immutable printer contract. Monetary amounts are integer currency units. */
data class TicketPrintData(
    val ticketId: UUID, val racecourseName: String, val raceDayNumber: String,
    val pointOfSaleNumber: Int, val cashierIdentifier: String,
    val betDateTime: LocalDateTime, val printDateTime: LocalDateTime,
    val raceNumber: Int, val betType: String, val unitsPerCombination: Long,
    val unitBetPrice: Long, val betString: String, val combinationCount: Int,
    val totalBetUnits: Long, val totalBetValue: Long, val currency: String,
    val isReprint: Boolean, val printAttemptId: UUID
) {
    init {
        require(racecourseName.isNotBlank() && raceDayNumber.matches(Regex("[0-9]{3}")) && raceDayNumber.toInt() in 1..999)
        require(pointOfSaleNumber > 0 && cashierIdentifier.isNotBlank() && raceNumber > 0)
        require(
            betType in setOf(
                "WIN",
                "QUINELLA",
                "EXACTA",
                "TRIFECTA"
            ) && betString.isNotBlank() && currency.isNotBlank()
        )
        require(unitsPerCombination > 0 && unitBetPrice > 0 && combinationCount > 0)
        require(totalBetUnits == Math.multiplyExact(combinationCount.toLong(), unitsPerCombination))
        require(totalBetValue == Math.multiplyExact(totalBetUnits, unitBetPrice))
        require(
            betDateTime == betDateTime.truncatedTo(ChronoUnit.MINUTES) && printDateTime == printDateTime.truncatedTo(
                ChronoUnit.MINUTES
            )
        )
    }
}

sealed interface PrintResult {
    data object Dispatched : PrintResult
    data class Failed(val message: String, val retryable: Boolean) : PrintResult
}

/** Adapter extension point. Inputs are already priced and resolved; implementations must not alter financial facts.
 * Dispatch may be repeated after a crash, so physical output can duplicate without another purchase.
 */
fun interface TicketPrinter {
    /** Dispatch can be repeated following a crash; this does not promise physical printing. */
    fun print(data: TicketPrintData): PrintResult
}
