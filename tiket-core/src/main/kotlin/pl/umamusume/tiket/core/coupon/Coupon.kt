package pl.umamusume.tiket.core.coupon

import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.BetUnits
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.money.moneySum
import pl.umamusume.tiket.core.validation.*
import java.time.Instant

@ConsistentCopyVisibility
data class CouponLeg internal constructor(val id: BetLegId, val bet: AtomicBet, val units: BetUnits, val stake: Money) {
    val couponId get() = id.couponId
    companion object {
        /** Restores one original leg; complete ownership/order checks belong to Coupon.reconstitute. */
        fun reconstitute(id: BetLegId, bet: AtomicBet, units: BetUnits, unitStake: Money,
                         stake: Money): Decision<CouponLeg> = decide {
            ensure(unitStake.amount > 0, ErrorCode.INVALID_STAKE, "unit stake must be positive")
            ensure(stake == unitStake * units.value, ErrorCode.INCONSISTENT_POOL, "leg stake differs from units × unit stake")
            CouponLeg(id, bet, units, stake)
        }
    }
}
class Coupon internal constructor(val id: CouponId, val raceDayId: RaceDayId, val raceId: RaceId,
    val playerId: PlayerId, val betType: BetType, val selection: CouponSelection,
    val unitsPerCombination: BetUnits, val unitStake: Money, val totalStake: Money,
    legs: List<CouponLeg>, val purchasedAt: Instant) {
    val legs = frozen(legs)
    companion object {
        /**
         * Restores original purchased legs, including subsequently refunded/scratched legs.
         * Legs must be in canonical purchase order. Never pass current eligibility as historical evidence.
         * Without purchaseEligibleRunners, completeness is checked only over the horses evidenced by the legs.
         */
        fun reconstitute(id: CouponId, raceDayId: RaceDayId, raceId: RaceId, playerId: PlayerId,
                         betType: BetType, selection: CouponSelection, unitsPerCombination: BetUnits,
                         unitStake: Money, totalStake: Money, legs: List<CouponLeg>, purchasedAt: Instant,
                         purchaseEligibleRunners: Set<HorseId>? = null): Decision<Coupon> = decide {
            val originalLegs = frozen(legs)
            ensure(unitStake.amount > 0, ErrorCode.INVALID_STAKE, "unit stake must be positive")
            ensure(originalLegs.isNotEmpty(), ErrorCode.INVALID_SELECTION, "coupon must contain legs")
            ensure(originalLegs.map { it.id }.distinct().size == originalLegs.size &&
                originalLegs.withIndex().all { (ordinal, leg) -> leg.id == BetLegId(id, ordinal) },
                ErrorCode.INCONSISTENT_POOL, "leg ownership or canonical ordinal mismatch")
            ensure(originalLegs.all { it.bet.type == betType && it.units == unitsPerCombination &&
                it.stake == unitStake * it.units.value },
                ErrorCode.INCONSISTENT_POOL, "leg type, quantity or stake mismatch")
            ensure(originalLegs.map { it.bet }.distinct().size == originalLegs.size,
                ErrorCode.INVALID_SELECTION, "duplicate atomic selection")
            ensure(moneySum(originalLegs.map { it.stake }) == totalStake,
                ErrorCode.INCONSISTENT_POOL, "coupon total differs from leg stakes")
            val universe = purchaseEligibleRunners ?: originalLegs.flatMap { it.bet.horses }.toSet()
            val expansion = SelectionExpander.expand(betType, selection, universe,
                universe, originalLegs.size).getOrThrow()
            ensure(expansion.bets == originalLegs.map { it.bet },
                ErrorCode.INVALID_SELECTION, "legs differ from original expression or canonical order")
            Coupon(id, raceDayId, raceId, playerId, betType, selection, unitsPerCombination,
                unitStake, totalStake, originalLegs, purchasedAt)
        }
    }
}
enum class LegOutcome { ACTIVE, REFUNDED, WON, LOST }
data class LegResolution(val legId: BetLegId, val outcome: LegOutcome, val amount: Money = Money.ZERO,
                         val settlementId: SettlementId? = null)
class CouponQuote internal constructor(val raceId: RaceId, val betType: BetType, val expansion: ExpansionResult,
    val unitsPerCombination: BetUnits, val unitStake: Money, val totalUnits: Long, val totalStake: Money) {
    val combinations get() = expansion.combinations
    val selection get() = expansion.selection
    /** Full comparable terms avoid hash collision and include the frozen expansion. */
    val terms: QuoteTerms get() = QuoteTerms(raceId, betType, selection, expansion.bets, unitsPerCombination, unitStake)
}
class QuoteTerms internal constructor(val raceId: RaceId, val betType: BetType, val selection: CouponSelection,
    bets: List<AtomicBet>, val units: BetUnits, val stake: Money) {
    val bets = frozen(bets)
    override fun equals(other: Any?) = other is QuoteTerms && raceId == other.raceId && betType == other.betType &&
        selection == other.selection && bets == other.bets && units == other.units && stake == other.stake
    override fun hashCode() = listOf(raceId, betType, selection, bets, units, stake).hashCode()
}
object CouponPricingCalculator {
    fun quote(raceId: RaceId, type: BetType, selection: CouponSelection, roster: Set<HorseId>,
              eligible: Set<HorseId>, units: Long, unitStake: Money, maximumLegs: Int = 10000): Decision<CouponQuote> = decide {
        val quantity = BetUnits(units)
        ensure(unitStake.amount > 0, ErrorCode.INVALID_STAKE, "unit stake must be positive")
        val expansion = SelectionExpander.expand(type,selection,roster,eligible,maximumLegs).getOrThrow()
        val totalUnits = Math.multiplyExact(expansion.combinations.toLong(), units)
        CouponQuote(raceId,type,expansion,quantity,unitStake,totalUnits,unitStake * totalUnits)
    }
}
