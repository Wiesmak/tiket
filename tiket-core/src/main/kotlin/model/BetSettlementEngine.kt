package pl.umamusume.tiket.core.model

interface BetSettlementEngine {
    fun evaluate(bet: AtomicBet, result: RaceResult): BetOutcome

    enum class BetOutcome {
        WON,
        LOST,
        REFUNDED,
    }
}