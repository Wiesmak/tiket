package pl.umamusume.tiket.core.pool

import java.math.BigInteger
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.*
import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.coupon.*
import pl.umamusume.tiket.core.validation.*

enum class NoWinnerPolicy { HOUSE_RETAINS_POOL }
data class PoolRules(val unitStake: Money, val takeRate: TakeRate) {
    init { ensure(unitStake.amount > 0, ErrorCode.INVALID_STAKE, "unit stake must be positive") }
}
class PoolSnapshot(val key: PoolKey, units: Map<AtomicBet, Long>, val validStake: Money, val version: Long = 0) {
    val units = frozenMap(units.toSortedMap(compareBy { it.canonical }))
    init {
        ensure(version >= 0 && units.all { it.key.type == key.betType && it.value > 0 },
            ErrorCode.INCONSISTENT_POOL, "invalid pool units")
    }
    companion object {
        fun aggregate(key: PoolKey, coupons: List<Coupon>, resolutions: Map<BetLegId, LegResolution> = emptyMap(),
                      version: Long = 0): Decision<PoolSnapshot> = decide {
            val units = linkedMapOf<AtomicBet, Long>()
            var stake = Money.ZERO
            val seen = hashSetOf<BetLegId>()
            coupons.filter { it.raceId == key.raceId && it.betType == key.betType }.forEach { coupon ->
                coupon.legs.forEach { leg ->
                    ensure(seen.add(leg.id), ErrorCode.INCONSISTENT_POOL, "duplicate leg")
                    if(resolutions[leg.id]?.outcome != LegOutcome.REFUNDED) {
                        units[leg.bet] = Math.addExact(units[leg.bet] ?: 0, leg.units.value)
                        stake += leg.stake
                    }
                }
            }
            PoolSnapshot(key, units, stake, version)
        }
    }
}
data class PoolSettlement(val key: PoolKey, val validStake: Money, val winningUnits: Long,
    val distributable: Money, val dividendPerUnit: Money, val distributed: Money, val houseAllocation: Money)
/** Integer parimutuel dividends. The house retains take, rounding remainder and no-winner pools.
 * Winning payouts already include any returned stake; callers must not add it again.
 */
object PoolSettlementCalculator {
    /** Also usable for reconciled aggregate stake/unit totals without materializing individual legs. */
    fun calculate(key: PoolKey, stake: Money, winningUnits: Long, take: TakeRate,
                  noWinnerPolicy: NoWinnerPolicy = NoWinnerPolicy.HOUSE_RETAINS_POOL): Decision<PoolSettlement> = decide {
        ensure(winningUnits >= 0 && (stake.amount > 0 || winningUnits == 0L),
            ErrorCode.INCONSISTENT_POOL, "invalid winning units")
        val distributable = Money(BigInteger.valueOf(stake.amount)
            .multiply(BigInteger.valueOf((10000-take.basisPoints).toLong())).divide(BigInteger.valueOf(10000)).longValueExact())
        val dividend = if(winningUnits == 0L) Money.ZERO else Money(distributable.amount / winningUnits)
        val distributed = dividend * winningUnits
        when(noWinnerPolicy) { NoWinnerPolicy.HOUSE_RETAINS_POOL -> Unit }
        PoolSettlement(key, stake, winningUnits, distributable, dividend, distributed, stake - distributed)
    }
    fun calculate(snapshot: PoolSnapshot, rules: PoolRules, winner: AtomicBet): Decision<PoolSettlement> = decide {
        ensure(winner.type == snapshot.key.betType, ErrorCode.INCONSISTENT_POOL, "winner type mismatch")
        val totalUnits = snapshot.units.values.fold(0L, Math::addExact)
        ensure(rules.unitStake * totalUnits == snapshot.validStake, ErrorCode.INCONSISTENT_POOL, "stake/unit mismatch")
        calculate(snapshot.key, snapshot.validStake, snapshot.units[winner] ?: 0, rules.takeRate).getOrThrow()
    }
}
