package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.coupon.LegOutcome
import pl.umamusume.tiket.core.coupon.LegResolution
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.pool.PoolSnapshot
import java.time.Instant
import java.util.*

@Repository
class PoolRepository(
    private val jdbc: JdbcTemplate,
    private val tx: PersistenceTransactions,
    private val races: RaceRepository
) {
    fun load(day: RaceDayId, key: PoolKey): PoolSnapshot? {
        if (races.load(day, key.raceId) == null) return null
        return jdbc.one(
            "SELECT s.*,r.unit_stake FROM pool_state s JOIN pool_rules r USING(race_id,bet_type) WHERE race_id=? AND bet_type=?",
            key.raceId.value,
            key.betType.name
        ) { r ->
            val units = jdbc.query(
                "SELECT * FROM pool_selection_units WHERE race_id=? AND bet_type=? ORDER BY canonical",
                { s, _ -> s.atomic() to s.getLong("units") },
                key.raceId.value,
                key.betType.name
            ).toMap()
            val total = units.values.fold(0L, Math::addExact)
            checked(
                total == r.getLong("active_units") && Money(r.getLong("unit_stake")) * total == r.money("valid_stake"),
                "Invalid pool aggregate"
            )
            PoolSnapshot(key, units, r.money("valid_stake"), r.getLong("version"))
        }
    }

    fun list(day: RaceDayId, race: RaceId): List<PoolSnapshot> =
        BetType.entries.mapNotNull { load(day, PoolKey(race, it)) }

    /** Only a core-authorized purchase delta may enter this path. Caller holds day/race/account locks. */
    fun acceptPurchase(day: RaceDayId, plan: pl.umamusume.tiket.core.betting.PurchasePlan): PoolSnapshot = tx.required {
        val key = plan.poolKey
        checked(day == plan.coupon.raceDayId, "Foreign purchase")
        checked(
            jdbc.queryForList(
                "SELECT race_id FROM pool_state WHERE race_id=? AND bet_type=? FOR UPDATE",
                key.raceId.value, key.betType.name
            ).size == 1, "Missing pool"
        )
        val old = load(day, key) ?: throw InvalidPersistenceException("Missing pool")
        val unitStake = jdbc.queryForObject(
            "SELECT unit_stake FROM pool_rules WHERE race_id=? AND bet_type=?",
            Long::class.java, key.raceId.value, key.betType.name
        )!!
        val addedUnits = plan.poolUnitIncreases.values.fold(0L, Math::addExact)
        checked(Money(unitStake) * addedUnits == plan.coupon.totalStake, "Invalid purchase delta")
        val totalUnits = Math.addExact(old.units.values.fold(0L, Math::addExact), addedUnits)
        val stake = old.validStake + plan.coupon.totalStake
        changed(
            jdbc.update(
                "UPDATE pool_state SET active_units=?,valid_stake=?,version=? WHERE race_id=? AND bet_type=? AND version=?",
                totalUnits,
                stake.amount,
                Math.incrementExact(old.version),
                key.raceId.value,
                key.betType.name,
                old.version
            )
        )
        jdbc.batchUpdate(
            "INSERT INTO pool_selection_units VALUES (?,?,?,?,?,?,?) ON CONFLICT(race_id,bet_type,canonical) DO UPDATE SET units=pool_selection_units.units+EXCLUDED.units",
            plan.poolUnitIncreases.map { (bet, units) ->
                Math.addExact(old.units[bet] ?: 0, units)
                arrayOf<Any?>(key.raceId.value, key.betType.name, *atomArgs(bet), units)
            })
        val next = load(day, key) ?: throw InvalidPersistenceException("Missing updated pool")
        checked(
            next.validStake == stake && next.version == Math.incrementExact(old.version),
            "Pool reconciliation failed"
        )
        next
    }


    /** Caller holds day, race, accounts and pool locks. Deltas come exclusively from the core plan. */
    fun applyRefundDelta(day: RaceDayId, expected: Long, key: PoolKey,
                         plan: pl.umamusume.tiket.core.settlement.RefundPlan): PoolSnapshot = tx.required {
        checked(plan.resultingState.raceId == key.raceId, "Foreign refund")
        val reduction = plan.poolReductions[key] ?: throw InvalidPersistenceException("Missing refund delta")
        val old = load(day, key) ?: throw InvalidPersistenceException("Missing pool")
        if (old.version != expected) throw StaleVersionException()
        val deltas = plan.atomicUnitReductions.filterKeys { it.type == key.betType }
        val units = deltas.values.fold(0L, Math::addExact)
        checked(units == reduction.units && units > 0, "Invalid refund units")
        val unitStake = jdbc.queryForObject("SELECT unit_stake FROM pool_rules WHERE race_id=? AND bet_type=?",
            Long::class.java, key.raceId.value, key.betType.name)!!
        checked(Money(unitStake) * units == reduction.stake, "Refund stake/unit mismatch")
        val total = Math.subtractExact(old.units.values.fold(0L, Math::addExact), units)
        checked(total >= 0 && old.validStake >= reduction.stake, "Refund exceeds pool")
        val stake = old.validStake - reduction.stake
        val remaining = deltas.map { (bet, n) ->
            val before = old.units[bet] ?: throw InvalidPersistenceException("Missing selection")
            checked(n > 0 && before >= n, "Refund exceeds selection")
            Triple(bet, before, Math.subtractExact(before, n))
        }
        changed(jdbc.update("UPDATE pool_state SET active_units=?,valid_stake=?,version=? WHERE race_id=? AND bet_type=? AND version=?",
            total, stake.amount, Math.incrementExact(expected), key.raceId.value, key.betType.name, expected))
        val updates = remaining.filter { it.third > 0 }
        val deletes = remaining.filter { it.third == 0L }
        val counts = jdbc.batchUpdate("UPDATE pool_selection_units SET units=? WHERE race_id=? AND bet_type=? AND canonical=? AND units=?",
            updates.map { (bet, before, after) -> arrayOf<Any?>(after, key.raceId.value, key.betType.name, bet.canonical, before) })
        counts.forEach(::changed)
        jdbc.batchUpdate("DELETE FROM pool_selection_units WHERE race_id=? AND bet_type=? AND canonical=? AND units=?",
            deletes.map { (bet, before, _) -> arrayOf<Any?>(key.raceId.value, key.betType.name, bet.canonical, before) }).forEach(::changed)
        val next = load(day, key) ?: throw InvalidPersistenceException("Missing reduced pool")
        checked(next.validStake == stake && next.units.values.fold(0L, Math::addExact) == total, "Refund reconciliation failed")
        next
    }

    fun update(day: RaceDayId, expected: Long, next: PoolSnapshot) = tx.required {
        require(next.version == Math.incrementExact(expected))
        checked(races.load(day, next.key.raceId) != null, "Foreign pool")
        val stake = jdbc.queryForObject(
            "SELECT unit_stake FROM pool_rules WHERE race_id=? AND bet_type=?",
            Long::class.java,
            next.key.raceId.value,
            next.key.betType.name
        )!!
        val total = next.units.values.fold(0L, Math::addExact)
        checked(Money(stake) * total == next.validStake, "Stake/unit mismatch")
        changed(
            jdbc.update(
                "UPDATE pool_state SET valid_stake=?,active_units=?,version=? WHERE race_id=? AND bet_type=? AND version=?",
                next.validStake.amount,
                total,
                next.version,
                next.key.raceId.value,
                next.key.betType.name,
                expected
            )
        )
        jdbc.update(
            "DELETE FROM pool_selection_units WHERE race_id=? AND bet_type=?",
            next.key.raceId.value,
            next.key.betType.name
        )
        next.units.forEach { (bet, units) ->
            jdbc.update(
                "INSERT INTO pool_selection_units VALUES (?,?,?,?,?,?,?)",
                next.key.raceId.value,
                next.key.betType.name,
                *atomArgs(bet),
                units
            )
        }; Unit
    }
}

@Repository
class LegResolutionRepository(private val jdbc: JdbcTemplate, private val coupons: CouponRepository) {
    fun insert(day: RaceDayId, resolution: LegResolution, at: Instant = Instant.now()) {
        val coupon =
            coupons.load(day, resolution.legId.couponId)?.coupon ?: throw InvalidPersistenceException("Foreign coupon")
        val leg = coupon.legs.single { it.id == resolution.legId }
        checked(resolution.outcome != LegOutcome.ACTIVE, "Only terminal resolutions are stored")
        checked(
            if (resolution.outcome == LegOutcome.REFUNDED) resolution.amount == leg.stake && resolution.settlementId == null
            else resolution.settlementId != null && (resolution.outcome != LegOutcome.LOST || resolution.amount == Money.ZERO),
            "Invalid resolution"
        )
        if (resolution.settlementId != null) checked(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement WHERE id=? AND race_id=?",
                Long::class.java,
                resolution.settlementId!!.value,
                coupon.raceId.value
            ) == 1L, "Foreign settlement"
        )
        jdbc.update(
            "INSERT INTO leg_resolution VALUES (?,?,?,?,?,?)",
            leg.id.couponId.value,
            leg.id.ordinal,
            resolution.outcome.name,
            resolution.amount.amount,
            resolution.settlementId?.value,
            timestamp(at)
        )
    }


    /** Validate against already reconstructed immutable coupons once, then batch terminal child writes. */
    fun insertAll(day: RaceDayId, history: List<pl.umamusume.tiket.core.coupon.Coupon>,
                  resolutions: List<LegResolution>, at: Instant = Instant.now()) {
        checked(history.all { it.raceDayId == day }, "Foreign history")
        val legs = history.flatMap { it.legs }.associateBy { it.id }
        resolutions.forEach { r ->
            val leg = legs[r.legId] ?: throw InvalidPersistenceException("Unknown leg")
            checked(r.outcome != LegOutcome.ACTIVE &&
                if (r.outcome == LegOutcome.REFUNDED) r.amount == leg.stake && r.settlementId == null
                else r.settlementId != null && (r.outcome != LegOutcome.LOST || r.amount == Money.ZERO),
                "Invalid terminal resolution")
        }
        jdbc.batchUpdate("INSERT INTO leg_resolution VALUES (?,?,?,?,?,?)", resolutions.map {
            arrayOf<Any?>(it.legId.couponId.value, it.legId.ordinal, it.outcome.name, it.amount.amount,
                it.settlementId?.value, timestamp(at))
        })
    }

    fun list(day: RaceDayId, race: RaceId): Map<BetLegId, LegResolution> = jdbc.query(
        "SELECT l.* FROM leg_resolution l JOIN coupon c ON c.id=l.coupon_id WHERE c.race_day_id=? AND c.race_id=? ORDER BY l.coupon_id,l.ordinal",
        { r, _ ->
            val id = BetLegId(CouponId(r.uuid("coupon_id")), r.getInt("ordinal"))
            id to LegResolution(
                id,
                LegOutcome.valueOf(r.getString("outcome")),
                r.money("amount"),
                r.getObject("settlement_id", UUID::class.java)?.let(::SettlementId)
            )
        },
        day.value,
        race.value
    ).toMap()
}
