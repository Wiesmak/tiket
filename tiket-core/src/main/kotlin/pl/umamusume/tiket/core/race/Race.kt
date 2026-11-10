package pl.umamusume.tiket.core.race

import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.validation.*

data class RaceRunner(val horseId: HorseId, val programNumber: String, val displayName: String)
class RaceDefinition(val id: RaceId, val number: Int, val title: String, runners: List<RaceRunner>) {
    val runners = frozen(runners)
    val roster = frozenSet(runners.map { it.horseId })
    init {
        ensure(number > 0 && runners.isNotEmpty() && roster.size == runners.size &&
            runners.map { it.programNumber }.distinct().size == runners.size,
            ErrorCode.INVALID_DEFINITION, "invalid race roster or number")
    }
}
enum class RacePhase { SCHEDULED, BETTING_OPEN, BETTING_CLOSED, RESULT_PROPOSED, RESULT_VERIFIED, SETTLED, VOIDED }
enum class ResultStatus { PROPOSED, VERIFIED }
enum class ResultKind { NORMAL, EXCEPTIONAL }
class RaceResult(val raceId: RaceId, val revision: String, finishers: List<HorseId>,
                 val status: ResultStatus, val kind: ResultKind = ResultKind.NORMAL) {
    val finishers = frozen(finishers)
}
class RaceState(val raceId: RaceId, val phase: RacePhase = RacePhase.SCHEDULED,
                scratched: Set<HorseId> = emptySet(), val result: RaceResult? = null, val version: Long = 0) {
    val scratched = frozenSet(scratched)
    init { require(version >= 0) }
    fun eligible(definition: RaceDefinition): Set<HorseId> = frozenSet(definition.roster - scratched)
}
object ResultValidator {
    fun validate(definition: RaceDefinition, state: RaceState, result: RaceResult): Decision<RaceResult> = decide {
        ensure(result.raceId == definition.id && state.raceId == definition.id &&
            result.revision.isNotBlank() && result.finishers.isNotEmpty() &&
            result.finishers.distinct().size == result.finishers.size &&
            state.eligible(definition).containsAll(result.finishers), ErrorCode.INVALID_RESULT, "invalid finishers or race")
        ensure(result.kind == ResultKind.NORMAL, ErrorCode.UNSUPPORTED_RESULT, "exceptional result requires administrative policy")
        result
    }
}
object RaceLifecyclePolicy {
    fun transition(definition: RaceDefinition, state: RaceState, target: RacePhase,
                   result: RaceResult? = state.result): Decision<RaceState> = decide {
        ensure(state.raceId == definition.id, ErrorCode.INVALID_TRANSITION, "race mismatch")
        ensure(state.phase != RacePhase.SETTLED, ErrorCode.ALREADY_SETTLED, "race is settled")
        val ordinary = when(state.phase) {
            RacePhase.SCHEDULED -> target == RacePhase.BETTING_OPEN
            RacePhase.BETTING_OPEN -> target == RacePhase.BETTING_CLOSED
            RacePhase.BETTING_CLOSED -> target == RacePhase.RESULT_PROPOSED
            RacePhase.RESULT_PROPOSED -> target in setOf(RacePhase.RESULT_PROPOSED, RacePhase.RESULT_VERIFIED)
            else -> false
        }
        ensure(ordinary || (target == RacePhase.VOIDED && state.phase !in setOf(RacePhase.RESULT_VERIFIED, RacePhase.VOIDED)),
            ErrorCode.INVALID_TRANSITION, "illegal race transition ${state.phase} -> $target")
        if (target in setOf(RacePhase.RESULT_PROPOSED, RacePhase.RESULT_VERIFIED)) {
            ensure(result != null, ErrorCode.INVALID_RESULT, "result required")
            ResultValidator.validate(definition, state, result!!).getOrThrow()
            ensure(result.status == if(target == RacePhase.RESULT_VERIFIED) ResultStatus.VERIFIED else ResultStatus.PROPOSED,
                ErrorCode.INVALID_RESULT, "result status mismatch")
            if(target == RacePhase.RESULT_VERIFIED) ensure(state.result?.finishers == result.finishers &&
                state.result.revision == result.revision, ErrorCode.INVALID_RESULT, "verification must confirm proposed revision")
        }
        RaceState(state.raceId, target, state.scratched, result, Math.incrementExact(state.version))
    }
}
