package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.umamusume.tiket.backend.application.CommandPrecondition
import pl.umamusume.tiket.backend.application.persisted
import pl.umamusume.tiket.core.accounting.MovementType
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.pool.*
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.raceday.RaceDayDefinition
import pl.umamusume.tiket.core.settlement.WinningSelectionResolver
import pl.umamusume.tiket.core.validation.getOrThrow

/** Caller holds the day lock for the entire proof and lifecycle update. */
@Repository
class FinalAccountingProof(
    private val jdbc: JdbcTemplate, private val pools: PoolRepository,
    private val settlements: SettlementRepository, private val entries: PlayerEntryRepository,
    private val accounts: AccountRepository, private val locks: PersistenceLocks
) {
    fun outstanding(day: RaceDayId): Set<BetLegId> = jdbc.query("""
        SELECT l.coupon_id,l.ordinal FROM coupon_leg l JOIN coupon c ON c.id=l.coupon_id
        WHERE c.race_day_id=? AND NOT EXISTS
        (SELECT 1 FROM leg_resolution r WHERE r.coupon_id=l.coupon_id AND r.ordinal=l.ordinal)
        ORDER BY l.coupon_id,l.ordinal
    """, { r, _ -> BetLegId(CouponId(r.uuid("coupon_id")), r.getInt("ordinal")) }, day.value).toSet()

    fun completion(day: RaceDayDefinition, states: List<RaceState>): Set<BetLegId> {
        if (states.any { it.phase !in setOf(RacePhase.SETTLED, RacePhase.VOIDED) })
            throw CommandPrecondition("Unresolved race")
        val missing = outstanding(day.id)
        if (missing.isNotEmpty()) throw CommandPrecondition("Unresolved purchased legs")
        checked(count("""
            SELECT count(*) FROM coupon_leg l JOIN coupon c ON c.id=l.coupon_id
            JOIN pool_rules p ON p.race_id=c.race_id AND p.bet_type=c.bet_type
            WHERE c.race_day_id=? AND (l.race_id<>c.race_id OR l.bet_type<>c.bet_type
              OR c.unit_stake<>p.unit_stake OR l.units<>c.units OR l.stake::numeric<>l.units::numeric*p.unit_stake)
        """, day.id.value) == 0L, "Purchased leg terms mismatch")
        for (race in day.races) {
            val state = states.single { it.raceId == race.id }
            val stored = persisted { settlements.forRace(day.id, race.id) }
            val snapshots = persisted { pools.list(day.id, race.id) }
            checked(snapshots.size == 4, "Missing pools")
            if (state.phase == RacePhase.VOIDED) {
                checked(stored.isEmpty() && snapshots.all { it.validStake.amount == 0L && it.units.isEmpty() },
                    "Voided race contains settlement or active pool")
                checked(count("""
                    SELECT count(*) FROM leg_resolution r JOIN coupon c ON c.id=r.coupon_id
                    WHERE c.race_id=? AND r.outcome<>'REFUNDED'
                """, race.id.value) == 0L, "Voided race has settled legs")
            } else {
                val result = state.result ?: throw InvalidPersistenceException("Missing verified result")
                persisted { ResultValidator.validate(race, state, result).getOrThrow() }
                checked(stored.size == 1 && stored.single().resultRevision == result.revision, "Missing or foreign settlement")
                val settlement = stored.single()
                for (snapshot in snapshots) {
                    val key = snapshot.key
                    val winner = persisted { WinningSelectionResolver.resolve(result, key.betType).getOrThrow() }
                    val expected = persisted { PoolSettlementCalculator.calculate(snapshot, day.pools.getValue(key), winner).getOrThrow() }
                    checked(settlement.pools[key] == expected && settlement.poolVersions[key] == snapshot.version,
                        "Settlement differs from pool calculation")
                    checked(count("""
                        SELECT count(*) FROM coupon_leg l JOIN coupon c ON c.id=l.coupon_id
                        JOIN leg_resolution r ON r.coupon_id=l.coupon_id AND r.ordinal=l.ordinal
                        WHERE c.race_id=? AND c.bet_type=? AND r.outcome<>'REFUNDED' AND
                        (r.settlement_id<>? OR r.outcome<>CASE WHEN l.canonical=? THEN 'WON' ELSE 'LOST' END
                         OR r.amount::numeric<>CASE WHEN l.canonical=? THEN l.units::numeric*? ELSE 0 END
                         OR EXISTS(SELECT 1 FROM race_scratch s WHERE s.race_id=c.race_id
                            AND s.horse_id IN (l.horse_1,l.horse_2,l.horse_3)))
                    """, race.id.value, key.betType.name, settlement.id.value, winner.canonical,
                        winner.canonical, expected.dividendPerUnit.amount) == 0L, "Invalid terminal result")
                }
            }
            // Aggregate concrete purchased legs in SQL; do not rebuild coupon selections.
            for (snapshot in snapshots) {
                val actual = persisted { jdbc.query("""
                    SELECT l.bet_type,l.canonical,l.horse_1,l.horse_2,l.horse_3,sum(l.units::numeric) AS total_units
                    FROM coupon_leg l JOIN coupon c ON c.id=l.coupon_id
                    JOIN leg_resolution r ON r.coupon_id=l.coupon_id AND r.ordinal=l.ordinal
                    WHERE c.race_id=? AND c.bet_type=? AND r.outcome<>'REFUNDED'
                    GROUP BY l.bet_type,l.canonical,l.horse_1,l.horse_2,l.horse_3
                """, { r, _ -> r.atomic() to r.getBigDecimal("total_units").longValueExact() },
                    race.id.value, snapshot.key.betType.name).toMap() }
                checked(actual == snapshot.units, "Pool projection differs from purchased legs")
            }
            checked(count("""
                SELECT count(*) FROM coupon_leg l JOIN coupon c ON c.id=l.coupon_id
                JOIN leg_resolution r ON r.coupon_id=l.coupon_id AND r.ordinal=l.ordinal
                WHERE c.race_id=? AND (
                  (r.outcome='REFUNDED' AND (r.amount<>l.stake OR NOT EXISTS
                    (SELECT 1 FROM account_movement m WHERE m.coupon_id=l.coupon_id AND m.leg_ordinal=l.ordinal
                     AND m.player_id=c.player_id AND m.race_day_id=c.race_day_id AND m.movement_type='LEG_REFUND' AND m.amount=r.amount)))
                  OR (r.outcome='WON' AND NOT EXISTS
                    (SELECT 1 FROM payout p JOIN account_movement m ON m.race_day_id=p.race_day_id
                     AND m.player_id=p.player_id AND m.movement_key=p.movement_key
                     WHERE p.coupon_id=l.coupon_id AND p.leg_ordinal=l.ordinal AND p.player_id=c.player_id
                     AND p.settlement_id=r.settlement_id AND p.amount=r.amount AND m.amount=r.amount
                     AND m.movement_type='WINNINGS' AND m.settlement_id=r.settlement_id
                     AND m.coupon_id=l.coupon_id AND m.leg_ordinal=l.ordinal))
                  OR (r.outcome='LOST' AND (r.amount<>0 OR EXISTS
                    (SELECT 1 FROM payout p WHERE p.coupon_id=l.coupon_id AND p.leg_ordinal=l.ordinal))))
            """, race.id.value) == 0L, "Terminal leg credit mismatch")
        }
        checked(count("""
            SELECT count(*) FROM account_movement m WHERE m.race_day_id=? AND
            ((m.movement_type='LEG_REFUND' AND NOT EXISTS
              (SELECT 1 FROM leg_resolution r WHERE r.coupon_id=m.coupon_id AND r.ordinal=m.leg_ordinal
               AND r.outcome='REFUNDED' AND r.amount=m.amount))
             OR (m.movement_type='WINNINGS' AND NOT EXISTS
              (SELECT 1 FROM payout p WHERE p.race_day_id=m.race_day_id AND p.player_id=m.player_id
               AND p.movement_key=m.movement_key AND p.coupon_id=m.coupon_id AND p.leg_ordinal=m.leg_ordinal
               AND p.settlement_id=m.settlement_id AND p.amount=m.amount))
             OR (m.movement_type IN ('FINAL_WITHDRAWAL','PROMOTIONAL_FORFEITURE')))
        """, day.id.value) == 0L, "Unexpected financial movement")
        checked(count("""
            SELECT count(*) FROM coupon c WHERE c.race_day_id=? AND NOT EXISTS
            (SELECT 1 FROM account_movement m WHERE m.coupon_id=c.id AND m.race_day_id=c.race_day_id
             AND m.player_id=c.player_id AND m.movement_type='COUPON_STAKE' AND m.amount=c.total_stake)
        """, day.id.value) == 0L, "Missing purchased stake debit")
        return missing
    }
    private fun count(sql: String, vararg args: Any?) = jdbc.queryForObject(sql, Long::class.java, *args)!!

    fun closure(day: RaceDayId): Boolean {
        val players = persisted { entries.list(day) }.map { it.playerId }
        checked(count("""
            SELECT count(*) FROM account_balance a WHERE a.race_day_id=? AND NOT EXISTS
            (SELECT 1 FROM player_entry e WHERE e.race_day_id=a.race_day_id AND e.player_id=a.player_id)
        """, day.value) == 0L, "Orphan account")
        locks.accounts(day, players)
        players.forEach { player ->
            val a = persisted { accounts.load(day, player) } ?: throw InvalidPersistenceException("Missing account")
            // load replays and validates the terminal pair, original amounts, projection and version.
            if (a.movements.lastOrNull()?.type != MovementType.PROMOTIONAL_FORFEITURE)
                throw CommandPrecondition("Outstanding account")
            checked(a.balance.amount == 0L, "Nonzero finalized account")
        }
        return true
    }
}
