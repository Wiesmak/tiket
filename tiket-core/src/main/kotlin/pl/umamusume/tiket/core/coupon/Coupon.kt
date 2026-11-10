package pl.umamusume.tiket.core.coupon

import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.BetUnits
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.validation.*
import java.time.Instant

@ConsistentCopyVisibility
data class CouponLeg internal constructor(val id: BetLegId, val bet: AtomicBet, val units: BetUnits, val stake: Money) {
    val couponId get() = id.couponId
}
class Coupon internal constructor(val id: CouponId, val raceDayId: RaceDayId, val raceId: RaceId,
    val playerId: PlayerId, val betType: BetType, val selection: CouponSelection,
    val unitsPerCombination: BetUnits, val unitStake: Money, val totalStake: Money,
    legs: List<CouponLeg>, val purchasedAt: Instant) {
    val legs = frozen(legs)
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
