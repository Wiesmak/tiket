package pl.umamusume.tiket.core.betting

import java.time.Instant
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.*
import pl.umamusume.tiket.core.coupon.*
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.raceday.*
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.validation.*

class PurchasePlan internal constructor(val coupon: Coupon, val debit: AccountMovement,
    val expectedDayVersion: Long, val expectedRaceVersion: Long, val expectedAccountVersion: Long) {
    val turnoverContribution get() = coupon.totalStake
    val poolKey get() = PoolKey(coupon.raceId,coupon.betType)
    val poolUnitIncreases = frozenMap(coupon.legs.associate { it.bet to it.units.value })
}
object BetPlacementPolicy {
    fun purchase(day: RaceDayDefinition, dayState: RaceDayState, race: RaceDefinition, state: RaceState,
                 enrollment: PlayerEntry?, account: AccountSnapshot, type: BetType, selection: CouponSelection,
                 units: Long, couponId: CouponId, timestamp: Instant, expectedQuote: QuoteTerms? = null): Decision<PurchasePlan> = decide {
        ensure(enrollment != null && enrollment == account.entry && enrollment.raceDayId == day.id,
            ErrorCode.PLAYER_NOT_ENROLLED, "player not enrolled in this event")
        ensure(dayState.phase == RaceDayPhase.RUNNING, ErrorCode.DAY_NOT_RUNNING, "day not running")
        ensure(day.races.any { it === race } && state.raceId == race.id && race.roster.containsAll(state.scratched),
            ErrorCode.INVALID_DEFINITION, "race context mismatch")
        ensure(state.phase == RacePhase.BETTING_OPEN, ErrorCode.BETTING_CLOSED, "race betting closed")
        ensure(account.movements.none { it.couponId == couponId }, ErrorCode.DUPLICATE_OPERATION, "coupon already debited")
        val rules = day.pools[PoolKey(race.id,type)] ?: reject(ErrorCode.INVALID_DEFINITION,"pool absent")
        val quote = CouponPricingCalculator.quote(race.id,type,selection,race.roster,state.eligible(race),
            units,rules.unitStake,day.rules.maximumCouponLegs).getOrThrow()
        ensure(expectedQuote == null || quote.terms == expectedQuote, ErrorCode.QUOTE_CHANGED, "quoted expansion or price changed")
        ensure(account.balance >= quote.totalStake, ErrorCode.INSUFFICIENT_BALANCE, "insufficient funds")
        val legs = quote.expansion.bets.mapIndexed { i, bet ->
            CouponLeg(BetLegId(couponId,i),bet,quote.unitsPerCombination,rules.unitStake * units)
        }
        val coupon = Coupon(couponId,day.id,race.id,account.entry.playerId,type,quote.selection,quote.unitsPerCombination,
            rules.unitStake,quote.totalStake,legs,timestamp)
        PurchasePlan(coupon,AccountMovement("stake:${couponId.value}",MovementType.COUPON_STAKE,quote.totalStake,couponId),
            dayState.version,state.version,account.version)
    }
}
