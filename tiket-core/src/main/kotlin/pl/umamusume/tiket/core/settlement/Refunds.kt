package pl.umamusume.tiket.core.settlement

import pl.umamusume.tiket.core.accounting.AccountMovement
import pl.umamusume.tiket.core.accounting.MovementType
import pl.umamusume.tiket.core.betting.AtomicBet
import pl.umamusume.tiket.core.coupon.Coupon
import pl.umamusume.tiket.core.coupon.LegOutcome
import pl.umamusume.tiket.core.coupon.LegResolution
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.race.RaceDefinition
import pl.umamusume.tiket.core.race.RacePhase
import pl.umamusume.tiket.core.race.RaceState
import pl.umamusume.tiket.core.validation.*

sealed interface RefundReason {
    data class Scratch(val horseId: HorseId) : RefundReason
    data object VoidRace : RefundReason
}
data class PoolReduction(val units: Long, val stake: Money)
class RefundPlan internal constructor(val expectedRaceVersion: Long, val resultingState: RaceState,
    resolutions: List<LegResolution>, credits: List<PlayerCredit>, couponTotals: Map<CouponId,Money>,
    playerTotals: Map<PlayerId,Money>, poolReductions: Map<PoolKey,PoolReduction>,
    atomicReductions: Map<AtomicBet,Long>) {
    val resolutions = frozen(resolutions)
    val credits = frozen(credits)
    val couponTotals = frozenMap(couponTotals)
    val playerTotals = frozenMap(playerTotals)
    val poolReductions = frozenMap(poolReductions)
    val atomicUnitReductions = frozenMap(atomicReductions)
    /** Positive amounts to subtract from qualifying turnover. */
    val turnoverReductions get() = playerTotals
}
/** Plans leg-level refunds from frozen purchased legs and existing terminal resolutions.
 * Previously refunded legs are skipped. Persist credits, reductions and race version atomically.
 */
object RefundPlanner {
    fun plan(race: RaceDefinition, state: RaceState, reason: RefundReason, coupons: List<Coupon>,
             existing: Map<BetLegId,LegResolution> = emptyMap()): Decision<RefundPlan> = decide {
        ensure(state.raceId == race.id && state.phase !in setOf(RacePhase.RESULT_VERIFIED,RacePhase.SETTLED),
            ErrorCode.INVALID_TRANSITION,"verified or settled race requires administrative correction")
        validateHistory(race,coupons,existing)
        if(reason is RefundReason.Scratch) {
            ensure(reason.horseId in race.roster,ErrorCode.UNKNOWN_HORSE,"scratch outside roster")
            ensure(state.phase != RacePhase.VOIDED,ErrorCode.INVALID_TRANSITION,"cannot scratch void race")
        }
        val resolutions = mutableListOf<LegResolution>()
        val credits = mutableListOf<PlayerCredit>()
        val couponTotals = linkedMapOf<CouponId,Money>()
        val playerTotals = linkedMapOf<PlayerId,Money>()
        val reductions = linkedMapOf<PoolKey,PoolReduction>()
        val atomic = linkedMapOf<AtomicBet,Long>()
        for(c in coupons.sortedBy { it.id.value.toString() }) for(leg in c.legs) {
            val affected = reason == RefundReason.VoidRace || (reason is RefundReason.Scratch && reason.horseId in leg.bet.horses)
            if(!affected || existing[leg.id]?.outcome == LegOutcome.REFUNDED) continue
            resolutions += LegResolution(leg.id,LegOutcome.REFUNDED,leg.stake)
            credits += PlayerCredit(c.playerId,AccountMovement("refund:${leg.id}",MovementType.LEG_REFUND,leg.stake,c.id,leg.id))
            couponTotals[c.id] = (couponTotals[c.id] ?: Money.ZERO) + leg.stake
            playerTotals[c.playerId] = (playerTotals[c.playerId] ?: Money.ZERO) + leg.stake
            val key = PoolKey(race.id,c.betType)
            val old = reductions[key] ?: PoolReduction(0,Money.ZERO)
            reductions[key] = PoolReduction(Math.addExact(old.units,leg.units.value),old.stake + leg.stake)
            atomic[leg.bet] = Math.addExact(atomic[leg.bet] ?: 0,leg.units.value)
        }
        val scratched = state.scratched + if(reason is RefundReason.Scratch) setOf(reason.horseId) else emptySet()
        val phase = if(reason == RefundReason.VoidRace) RacePhase.VOIDED else state.phase
        val changed = resolutions.isNotEmpty() || scratched != state.scratched || phase != state.phase
        // A proposed result is invalidated by a new scratch.
        val invalidate = reason is RefundReason.Scratch && changed && phase == RacePhase.RESULT_PROPOSED
        val next = RaceState(race.id,if(invalidate) RacePhase.BETTING_CLOSED else phase,scratched,
            if(invalidate || reason == RefundReason.VoidRace) null else state.result,if(changed) Math.incrementExact(state.version) else state.version)
        RefundPlan(state.version,next,resolutions,credits,couponTotals,playerTotals,reductions,atomic)
    }
}
