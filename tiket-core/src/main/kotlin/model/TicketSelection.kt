package pl.umamusume.tiket.core.model

sealed interface TicketSelection {
    sealed interface Win : TicketSelection {
        data class Single(val selection: PositionSelection) : Win
        data class Box(val horses: Set<HorseId>) : Win
    }

    sealed interface Quinella: TicketSelection {
        data class Single(val selection: Pair<PositionSelection, PositionSelection>) : Quinella
        data class Box(val horses: Set<HorseId>) : Quinella
    }

    sealed interface Exacta: TicketSelection {
        data class Single(val selection: Pair<PositionSelection, PositionSelection>) : Exacta
        data class Box(val horses: Set<HorseId>) : Exacta
    }

    sealed interface Trifecta: TicketSelection {
        data class Single(val selection: Triple<PositionSelection, PositionSelection, PositionSelection>) : Trifecta
        data class Box(val horses: Set<HorseId>) : Trifecta
    }
}