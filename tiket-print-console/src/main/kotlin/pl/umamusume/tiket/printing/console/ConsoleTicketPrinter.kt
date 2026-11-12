package pl.umamusume.tiket.printing.console

import pl.umamusume.tiket.printing.*
import java.io.PrintStream
import java.time.format.DateTimeFormatter

/** Development/log dispatch. Success confirms only a flushed stream, never physical receipt acknowledgement. */
class ConsoleTicketPrinter(private val output: PrintStream = System.out) : TicketPrinter {
    override fun print(data: TicketPrintData): PrintResult {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val ticket = with(data) {
            """
            |========== TIKET ==========
            |Ticket: $ticketId
            |Racecourse: $racecourseName
            |Race day: $raceDayNumber  Race: $raceNumber
            |POS: $pointOfSaleNumber  Cashier: $cashierIdentifier
            |Bet time: ${betDateTime.format(format)}
            |Print time: ${printDateTime.format(format)}
            |Bet type: $betType  Selection: $betString
            |Units per combination: $unitsPerCombination
            |Unit bet price: $unitBetPrice $currency
            |Combinations: $combinationCount
            |Total bet units: $totalBetUnits
            |Total bet value: $totalBetValue $currency
            |Reprint: $isReprint
            |Print attempt: $printAttemptId
            |Console dispatch only
            |===========================
            """.trimMargin()
        }
        synchronized(output) {
            output.println(ticket)
            output.flush()
            if (output.checkError()) return PrintResult.Failed("stdout write failed", true)
        }
        return PrintResult.Dispatched
    }
}
