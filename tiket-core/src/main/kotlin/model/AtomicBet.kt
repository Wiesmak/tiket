package pl.umamusume.tiket.core.model

sealed interface AtomicBet {
    data class Win(val horse: HorseId) : AtomicBet
    data class Quinella(val horses: Set<HorseId>) : AtomicBet
    data class Exacta(val horses: Pair<HorseId, HorseId>) : AtomicBet
    data class Trifecta(val horses: Triple<HorseId, HorseId, HorseId>) : AtomicBet
}