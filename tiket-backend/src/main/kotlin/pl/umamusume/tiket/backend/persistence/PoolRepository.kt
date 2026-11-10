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
