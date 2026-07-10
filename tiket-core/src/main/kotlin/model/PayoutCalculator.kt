package pl.umamusume.tiket.core.model

interface PayoutCalculator {
    fun calculatePayout(
        ticket: Ticket,
        winningBets: List<AtomicBet>,
        raceId: RaceId,
    ): Money
}