package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.pool.PoolSettlement
import pl.umamusume.tiket.core.race.ResultStatus

data class StoredSettlement(
    val id: SettlementId,
    val raceId: RaceId,
    val resultRevision: String,
    val pools: Map<PoolKey, PoolSettlement>,
    val poolVersions: Map<PoolKey, Long>
)

data class StoredPayout(
    val settlementId: SettlementId,
    val playerId: PlayerId,
    val legId: BetLegId,
    val amount: Money,
    val movementKey: String
)

@Repository
class SettlementRepository(
    private val jdbc: JdbcTemplate,
    private val tx: PersistenceTransactions,
    private val results: ResultRepository
) {
    fun insert(day: RaceDayId, record: StoredSettlement) = tx.required {
        val result = results.load(day, record.raceId, record.resultRevision)
            ?: throw InvalidPersistenceException("Missing settlement result")
        checked(result.status == ResultStatus.VERIFIED, "Settlement requires verified result")
        checked(record.pools.keys == BetType.entries.map { PoolKey(record.raceId, it) }
            .toSet() && record.poolVersions.keys == record.pools.keys, "Four pool records required")
        jdbc.update(
            "INSERT INTO settlement VALUES (?,?,?)",
            record.id.value,
            record.raceId.value,
            record.resultRevision
        )
        record.pools.forEach { (key, s) ->
            checked(
                s.key == key && s.winningUnits >= 0 && s.distributed == s.dividendPerUnit * s.winningUnits && s.validStake == s.distributed + s.houseAllocation && s.distributed <= s.distributable && s.distributable <= s.validStake,
                "Invalid settlement arithmetic"
            )
            jdbc.update(
                "INSERT INTO pool_settlement VALUES (?,?,?,?,?,?,?,?,?,?)",
                record.id.value,
                key.raceId.value,
                key.betType.name,
                s.validStake.amount,
                s.winningUnits,
                s.distributable.amount,
                s.dividendPerUnit.amount,
                s.distributed.amount,
                s.houseAllocation.amount,
                record.poolVersions.getValue(key)
            )
        }; Unit
    }

    fun load(day: RaceDayId, id: SettlementId): StoredSettlement? = jdbc.one(
        "SELECT s.* FROM settlement s JOIN race r ON r.id=s.race_id WHERE r.race_day_id=? AND s.id=?",
        day.value,
        id.value
    ) { r ->
        val versions = linkedMapOf<PoolKey, Long>()
        val pools = jdbc.query("SELECT * FROM pool_settlement WHERE settlement_id=? ORDER BY bet_type", { s, _ ->
            val key = PoolKey(RaceId(s.uuid("race_id")), BetType.valueOf(s.getString("bet_type")))
            versions[key] = s.getLong("pool_version")
            key to PoolSettlement(
                key,
                s.money("valid_stake"),
                s.getLong("winning_units"),
                s.money("distributable"),
                s.money("dividend_per_unit"),
                s.money("distributed"),
                s.money("house_allocation")
            )
        }, id.value).toMap()
        checked(pools.size == 4, "Incomplete stored settlement")
        StoredSettlement(
            id,
            RaceId(r.uuid("race_id")),
            r.getString("result_revision"),
            java.util.Map.copyOf(pools),
            java.util.Map.copyOf(versions)
        )
    }

    fun forRace(day: RaceDayId, race: RaceId): List<StoredSettlement> = jdbc.query(
        "SELECT s.id FROM settlement s JOIN race r ON r.id=s.race_id WHERE r.race_day_id=? AND r.id=? ORDER BY s.id",
        { r, _ -> SettlementId(r.uuid("id")) },
        day.value,
        race.value
    ).map { load(day, it)!! }

    fun insertPayout(day: RaceDayId, payout: StoredPayout) {
        val matches = jdbc.queryForObject(
            """SELECT count(*) FROM account_movement m JOIN leg_resolution l ON l.coupon_id=m.coupon_id AND l.ordinal=m.leg_ordinal
 WHERE m.race_day_id=? AND m.player_id=? AND m.movement_key=? AND m.coupon_id=? AND m.leg_ordinal=?
 AND m.movement_type='WINNINGS' AND m.amount=? AND m.settlement_id=? AND l.settlement_id=m.settlement_id AND l.outcome='WON' AND l.amount=m.amount""",
            Long::class.java,
            day.value,
            payout.playerId.value,
            payout.movementKey,
            payout.legId.couponId.value,
            payout.legId.ordinal,
            payout.amount.amount,
            payout.settlementId.value
        )
        checked(matches == 1L, "Payout must match winning resolution and credited movement")
        jdbc.update(
            "INSERT INTO payout VALUES (?,?,?,?,?,?,?)",
            payout.settlementId.value,
            day.value,
            payout.playerId.value,
            payout.legId.couponId.value,
            payout.legId.ordinal,
            payout.amount.amount,
            payout.movementKey
        )
    }

    fun payouts(day: RaceDayId, id: SettlementId): List<StoredPayout> = jdbc.query(
        "SELECT * FROM payout WHERE race_day_id=? AND settlement_id=? ORDER BY coupon_id,leg_ordinal",
        { r, _ ->
            StoredPayout(
                id,
                PlayerId(r.uuid("player_id")),
                BetLegId(CouponId(r.uuid("coupon_id")), r.getInt("leg_ordinal")),
                r.money("amount"),
                r.getString("movement_key")
            )
        },
        day.value,
        id.value
    )
}
