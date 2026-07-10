package pl.umamusume.tiket.core.model

data class Ticket(
    val id: TicketId,
    val raceId: RaceId,
    val baseStake: Money,
    val selection: TicketSelection,
)