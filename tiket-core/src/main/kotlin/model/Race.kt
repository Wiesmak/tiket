package pl.umamusume.tiket.core.model

data class Race(
    val id: RaceId,
    val name: String,
    val fieldSize: Int,
    val distance: Int,
    val group: RaceGroup,
    val racecourse: String,
    val date: String,

    val status: RaceStatus,
    val validRunners: Set<HorseId>,
    val scratchedRunners: Set<HorseId>,
) {

    fun canAcceptBets(): Boolean = status == RaceStatus.OPEN_FOR_BETTING

    enum class RaceGroup(val group: Int) {
        G1(1),
        G2(2),
        G3(3),
        OP(4),
    }

    enum class RaceStatus(val status: Int) {
        SCHEDULED(0),
        OPEN_FOR_BETTING(1),
        CLOSED(2),
        FINISHED(3),
        SETTLED(4),
    }
}