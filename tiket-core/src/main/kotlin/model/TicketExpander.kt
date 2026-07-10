package pl.umamusume.tiket.core.model

interface TicketExpander {
    fun expand(selection: TicketSelection, validRunners: Set<HorseId>): List<AtomicBet>
}