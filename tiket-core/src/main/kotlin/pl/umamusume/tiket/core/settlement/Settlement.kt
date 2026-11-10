package pl.umamusume.tiket.core.settlement

import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.*
import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.coupon.*
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.pool.*
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.validation.*

object WinningSelectionResolver {
    fun resolve(result: RaceResult, type: BetType): Decision<AtomicBet> = decide {
        ensure(result.status == ResultStatus.VERIFIED, ErrorCode.RESULT_NOT_VERIFIED, "result not verified")
        ensure(result.kind == ResultKind.NORMAL, ErrorCode.UNSUPPORTED_RESULT, "exceptional result")
        ensure(result.finishers.distinct().size == result.finishers.size && result.finishers.size >= type.positions,
            ErrorCode.INVALID_RESULT, "insufficient or repeated finishers")
        AtomicBet.create(type,result.finishers.take(type.positions)).getOrThrow()
    }
}
object CouponPayoutCalculator {
    fun payout(leg: CouponLeg, winner: AtomicBet, settlement: PoolSettlement): Decision<Money> = decide {
        ensure(leg.bet.type == settlement.key.betType && winner.type == leg.bet.type,
            ErrorCode.INCONSISTENT_POOL, "payout pool mismatch")
        if(leg.bet == winner) settlement.dividendPerUnit * leg.units.value else Money.ZERO
    }
}
data class PlayerCredit(val playerId: PlayerId, val movement: AccountMovement)
class RaceSettlementPlan internal constructor(val id: SettlementId, val raceId: RaceId,
    val expectedRaceVersion: Long, val resultRevision: String, pools: Map<PoolKey,PoolSettlement>,
    poolVersions: Map<PoolKey,Long>, resolutions: List<LegResolution>, credits: List<PlayerCredit>,
    couponPayouts: Map<CouponId,Money>, playerPayouts: Map<PlayerId,Money>, val resultingState: RaceState) {
    val pools = frozenMap(pools)
    val expectedPoolVersions = frozenMap(poolVersions)
    val resolutions = frozen(resolutions)
    val credits = frozen(credits)
    val couponPayouts = frozenMap(couponPayouts)
    val playerPayouts = frozenMap(playerPayouts)
}
internal fun validateHistory(race: RaceDefinition, coupons: List<Coupon>, resolutions: Map<BetLegId,LegResolution>) {
    ensure(coupons.all { it.raceId == race.id } && coupons.map { it.id }.distinct().size == coupons.size,
        ErrorCode.INCONSISTENT_POOL, "foreign or duplicate coupons")
    val legs = coupons.flatMap { it.legs }.associateBy { it.id }
    ensure(resolutions.all { (id,r) -> id == r.legId && id in legs &&
        (r.outcome == LegOutcome.ACTIVE || (r.outcome == LegOutcome.REFUNDED && r.amount == legs.getValue(id).stake)) },
        ErrorCode.INCONSISTENT_POOL, "unknown, settled or invalid resolution")
    coupons.forEach { c ->
        ensure(c.legs.isNotEmpty() && c.legs.map { it.id }.distinct().size == c.legs.size &&
            c.legs.all { it.couponId == c.id && it.bet.type == c.betType && race.roster.containsAll(it.bet.horses) &&
                it.units == c.unitsPerCombination && it.stake == c.unitStake * it.units.value } &&
            moneySum(c.legs.map { it.stake }) == c.totalStake, ErrorCode.INCONSISTENT_POOL,"invalid coupon legs")
    }
}
object FinancialReconciliation {
    fun validate(original: Money, refunds: Money, payouts: Money, house: Money): Decision<Unit> = decide {
        ensure(original == refunds + payouts + house, ErrorCode.INCONSISTENT_POOL, "financial conservation failed")
    }
}
object RaceSettlementPlanner {
    fun plan(id: SettlementId, race: RaceDefinition, state: RaceState, rules: Map<BetType,PoolRules>,
             snapshots: Map<PoolKey,PoolSnapshot>, coupons: List<Coupon>,
             existing: Map<BetLegId,LegResolution> = emptyMap()): Decision<RaceSettlementPlan> = decide {
        ensure(state.phase != RacePhase.SETTLED, ErrorCode.ALREADY_SETTLED, "race already settled")
        ensure(state.raceId == race.id && state.phase == RacePhase.RESULT_VERIFIED && state.result != null,
            ErrorCode.RESULT_NOT_VERIFIED, "verified state required")
        val result = ResultValidator.validate(race,state,state.result!!).getOrThrow()
        ensure(rules.keys == BetType.entries.toSet() &&
            snapshots.keys == BetType.entries.map { PoolKey(race.id,it) }.toSet(),
            ErrorCode.INCONSISTENT_POOL, "exactly four pool configurations and snapshots required")
        validateHistory(race,coupons,existing)
        val settlements = linkedMapOf<PoolKey,PoolSettlement>()
        val resolutions = mutableListOf<LegResolution>()
        val credits = mutableListOf<PlayerCredit>()
        val couponTotals = linkedMapOf<CouponId,Money>()
        val playerTotals = linkedMapOf<PlayerId,Money>()
        for(type in BetType.entries) {
            val key = PoolKey(race.id,type)
            val snapshot = snapshots.getValue(key)
            val aggregate = PoolSnapshot.aggregate(key,coupons,existing).getOrThrow()
            ensure(snapshot.key == key && snapshot.units == aggregate.units && snapshot.validStake == aggregate.validStake,
                ErrorCode.INCONSISTENT_POOL, "projection differs from active legs: $key")
            val winner = WinningSelectionResolver.resolve(result,type).getOrThrow()
            val settlement = PoolSettlementCalculator.calculate(snapshot,rules.getValue(type),winner).getOrThrow()
            settlements[key] = settlement
            var actualPayouts = Money.ZERO
            for(c in coupons.filter { it.betType == type }.sortedBy { it.id.value.toString() }) {
                ensure(c.unitStake == rules.getValue(type).unitStake,ErrorCode.INCONSISTENT_POOL,"frozen stake mismatch")
                var total = Money.ZERO
                for(leg in c.legs) {
                    if(existing[leg.id]?.outcome == LegOutcome.REFUNDED) continue
                    ensure(leg.bet.horses.none { it in state.scratched },ErrorCode.INCONSISTENT_POOL,"unrefunded scratched leg")
                    val won = leg.bet == winner
                    val payout = CouponPayoutCalculator.payout(leg,winner,settlement).getOrThrow()
                    resolutions += LegResolution(leg.id,if(won) LegOutcome.WON else LegOutcome.LOST,payout,id)
                    if(won) credits += PlayerCredit(c.playerId,AccountMovement("win:${leg.id}",MovementType.WINNINGS,
                        payout,c.id,leg.id,id))
                    total += payout
                }
                couponTotals[c.id] = total
                playerTotals[c.playerId] = (playerTotals[c.playerId] ?: Money.ZERO) + total
                actualPayouts += total
            }
            ensure(actualPayouts == settlement.distributed,ErrorCode.INCONSISTENT_POOL,"payout totals disagree")
            val historical = moneySum(coupons.filter { it.betType == type }.map { it.totalStake })
            val refunds = moneySum(coupons.filter { it.betType == type }.flatMap { it.legs }
                .filter { existing[it.id]?.outcome == LegOutcome.REFUNDED }.map { it.stake })
            FinancialReconciliation.validate(historical,refunds,actualPayouts,settlement.houseAllocation).getOrThrow()
        }
        RaceSettlementPlan(id,race.id,state.version,result.revision,settlements,snapshots.mapValues { it.value.version },
            resolutions,credits,couponTotals,playerTotals,
            RaceState(race.id,RacePhase.SETTLED,state.scratched,result,Math.incrementExact(state.version)))
    }
}
