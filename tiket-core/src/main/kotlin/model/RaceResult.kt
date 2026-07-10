package pl.umamusume.tiket.core.model

data class RaceResult(
    val raceId: RaceId,
    val topFinishers: List<HorseId>
) {
    fun first(): HorseId? = topFinishers.getOrNull(0)
    fun second(): HorseId? = topFinishers.getOrNull(1)
    fun third(): HorseId? = topFinishers.getOrNull(2)
}