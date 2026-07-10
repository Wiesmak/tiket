package pl.umamusume.tiket.core.usecase

import pl.umamusume.tiket.core.model.BetSettlementEngine
import pl.umamusume.tiket.core.model.PayoutCalculator
import pl.umamusume.tiket.core.model.RaceResult
import pl.umamusume.tiket.core.model.TicketExpander
import pl.umamusume.tiket.core.repository.TicketRepository
import pl.umamusume.tiket.core.repository.WalletService

class RaceSettlementUseCase(
    private val ticketRepository: TicketRepository,
    private val ticketExpander: TicketExpander,
    private val settlementEngine: BetSettlementEngine,
    private val payoutCalculator: PayoutCalculator,
    private val walletService: WalletService,
) {
    fun settleRace(result: RaceResult) {
        ticketRepository.getTicketsForRace(result.raceId).forEach { ticket ->
            val atomicBets = ticketExpander.expand(ticket)
            atomicBets.forEach { bet ->
                val outcome = settlementEngine.evaluate(bet, result)
                when (outcome) {
                    BetSettlementEngine.BetOutcome.WON -> {
                        val payout = payoutCalculator.calculatePayout(bet, result)
                        walletService.credit(bet.userId, payout)
                    }
                    BetSettlementEngine.BetOutcome.LOST -> {
                        // No action needed for lost bets
                    }
                    BetSettlementEngine.BetOutcome.REFUNDED -> {
                        walletService.credit(bet.userId, bet.amount)
                    }
                }
            }
        }

    }
}