package pl.umamusume.tiket.core.model

sealed interface PositionSelection {
    data class Specific(val horse: HorseId) : PositionSelection
    data object Wall : PositionSelection
}