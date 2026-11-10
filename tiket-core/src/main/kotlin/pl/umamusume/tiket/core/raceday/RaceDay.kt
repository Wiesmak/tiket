package pl.umamusume.tiket.core.raceday

import java.time.LocalDate
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.pool.*
import pl.umamusume.tiket.core.validation.*

data class RaceDayRules(val maximumCouponLegs: Int = 10000, val maximumOpenRaces: Int = 1,
                        val noWinnerPolicy: NoWinnerPolicy = NoWinnerPolicy.HOUSE_RETAINS_POOL) {
    init { require(maximumCouponLegs > 0 && maximumOpenRaces > 0) }
}
class RaceDayDefinition(val id: RaceDayId, val date: LocalDate, val currency: String,
                        races: List<RaceDefinition>, val rulesVersion: String,
                        pools: Map<PoolKey, PoolRules>, val rules: RaceDayRules = RaceDayRules()) {
    val races = frozen(races)
    val pools = frozenMap(pools)
    init {
        ensure(currency.isNotBlank() && rulesVersion.isNotBlank() && races.isNotEmpty() &&
            races.map { it.id }.distinct().size == races.size &&
            races.map { it.number }.distinct().size == races.size &&
            pools.keys == races.flatMap { r -> BetType.entries.map { PoolKey(r.id,it) } }.toSet(),
            ErrorCode.INVALID_DEFINITION, "definition requires unique races and exactly four pools per race")
    }
}
enum class RaceDayPhase { DRAFT, READY, RUNNING, FINALIZING, COMPLETED, CLOSED }
data class RaceDayState(val phase: RaceDayPhase = RaceDayPhase.DRAFT, val version: Long = 0) {
    init { require(version >= 0) }
}
object RaceDayLifecyclePolicy {
    fun enrollmentAllowed(state: RaceDayState) = state.phase in setOf(RaceDayPhase.DRAFT, RaceDayPhase.READY)
    fun depositAllowed(state: RaceDayState) = enrollmentAllowed(state)
    fun definitionChangesAllowed(state: RaceDayState) = state.phase == RaceDayPhase.DRAFT
    fun transition(definition: RaceDayDefinition, state: RaceDayState, target: RaceDayPhase,
                   races: List<RaceState>, outstandingLegs: Set<BetLegId> = emptySet(),
                   accountsCashedOut: Boolean = false): Decision<RaceDayState> = decide {
        ensure(target.ordinal == state.phase.ordinal + 1, ErrorCode.INVALID_TRANSITION, "illegal day transition")
        ensure(races.map { it.raceId }.toSet() == definition.races.map { it.id }.toSet() &&
            races.size == definition.races.size, ErrorCode.INVALID_TRANSITION, "race snapshots incomplete")
        if(target == RaceDayPhase.FINALIZING) ensure(races.none { it.phase == RacePhase.BETTING_OPEN },
            ErrorCode.INVALID_TRANSITION, "close betting first")
        if(target == RaceDayPhase.COMPLETED) ensure(races.all { it.phase in setOf(RacePhase.SETTLED,RacePhase.VOIDED) } &&
            outstandingLegs.isEmpty(), ErrorCode.INVALID_TRANSITION, "unresolved races/legs")
        if(target == RaceDayPhase.CLOSED) ensure(accountsCashedOut, ErrorCode.INVALID_TRANSITION, "cashout obligations remain")
        RaceDayState(target, Math.incrementExact(state.version))
    }
    fun openBetting(definition: RaceDayDefinition, day: RaceDayState, raceId: RaceId,
                    states: List<RaceState>): Decision<RaceState> = decide {
        ensure(day.phase == RaceDayPhase.RUNNING, ErrorCode.DAY_NOT_RUNNING, "day not running")
        ensure(states.size == definition.races.size && states.map { it.raceId }.toSet() == definition.races.map { it.id }.toSet(),
            ErrorCode.INVALID_TRANSITION, "incomplete race states")
        ensure(states.count { it.phase == RacePhase.BETTING_OPEN } < definition.rules.maximumOpenRaces,
            ErrorCode.INVALID_TRANSITION, "too many open races")
        val race = definition.races.singleOrNull { it.id == raceId }
            ?: reject(ErrorCode.INVALID_TRANSITION, "unknown race")
        RaceLifecyclePolicy.transition(race, states.single { it.raceId == raceId }, RacePhase.BETTING_OPEN).getOrThrow()
    }
}
