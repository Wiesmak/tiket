package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.validation.getOrThrow
import java.time.Instant
import java.util.*

@Repository
class PlayerEntryRepository(private val jdbc: JdbcTemplate, private val tx: PersistenceTransactions) {
    fun insert(entry: PlayerEntry) = tx.required {
        jdbc.update(
            "INSERT INTO player_entry VALUES (?,?,?,?)",
            entry.raceDayId.value,
            entry.playerId.value,
            entry.openingPaid.amount,
            entry.openingFreebet.amount
        )
        jdbc.update(
            "INSERT INTO account_balance VALUES (?,?,?,0,0,0,0,false)",
            entry.raceDayId.value,
            entry.playerId.value,
            entry.openingBalance.amount
        ); Unit
    }

    fun load(day: RaceDayId, player: PlayerId): PlayerEntry? =
        jdbc.one("SELECT * FROM player_entry WHERE race_day_id=? AND player_id=?", day.value, player.value) {
            PlayerEntry(day, player, it.money("opening_paid"), it.money("opening_freebet"))
        }
}

@Repository
class AccountRepository(
    private val jdbc: JdbcTemplate,
    private val tx: PersistenceTransactions,
    private val entries: PlayerEntryRepository
) {
    fun movements(day: RaceDayId, player: PlayerId): List<AccountMovement> =
        jdbc.query("SELECT * FROM account_movement WHERE race_day_id=? AND player_id=? ORDER BY sequence", { r, _ ->
            val coupon = r.getObject("coupon_id", UUID::class.java)?.let(::CouponId)
            val ordinal = r.getObject("leg_ordinal") as? Int
            AccountMovement(
                r.getString("movement_key"),
                MovementType.valueOf(r.getString("movement_type")),
                r.money("amount"),
                coupon,
                ordinal?.let { BetLegId(coupon!!, it) },
                r.getObject("settlement_id", UUID::class.java)?.let(::SettlementId)
            )
        }, day.value, player.value)

    fun projection(day: RaceDayId, player: PlayerId): AccountProjection? =
        jdbc.one("SELECT * FROM account_balance WHERE race_day_id=? AND player_id=?", day.value, player.value) {
            AccountProjection(
                it.money("balance"),
                it.money("accepted_stakes"),
                it.money("refunded_stakes"),
                it.money("winnings")
            )
        }

    fun load(day: RaceDayId, player: PlayerId): AccountSnapshot? {
        val entry = entries.load(day, player) ?: return null
        return jdbc.one(
            "SELECT * FROM account_balance WHERE race_day_id=? AND player_id=?",
            day.value,
            player.value
        ) { r ->
            val history = movements(day, player)
            val account = AccountSnapshot.reconstitute(
                entry, history, r.getLong("version"),
                AccountProjection(
                    r.money("balance"),
                    r.money("accepted_stakes"),
                    r.money("refunded_stakes"),
                    r.money("winnings")
                )
            ).getOrThrow()
            checked(
                r.getBoolean("cashed_out") == history.any { it.type == MovementType.FINAL_WITHDRAWAL },
                "Cashout projection mismatch"
            )
            account
        } ?: throw InvalidPersistenceException("Missing account projection")
    }

    /** Validates complete history, appends suffix and advances projection atomically. No balance-only writes. */
    fun append(expected: Long, next: AccountSnapshot, at: Instant = Instant.now()) = tx.required {
        val day = next.entry.raceDayId;
        val player = next.entry.playerId
        // Serialize the projection read with its ledger read before validating the suffix.
        val lockedVersion = jdbc.one(
            "SELECT version FROM account_balance WHERE race_day_id=? AND player_id=? FOR UPDATE",
            day.value, player.value
        ) { it.getLong("version") }
        if (lockedVersion != expected) throw StaleVersionException()
        val previous = load(day, player) ?: throw InvalidPersistenceException("Missing enrollment")
        if (previous.version != expected) throw StaleVersionException()
        checked(
            previous.entry == next.entry && next.movements.take(previous.movements.size) == previous.movements,
            "Ledger history cannot change"
        )
        val additions = next.movements.drop(previous.movements.size)
        checked(additions.isNotEmpty(), "No new movements")
        AccountSnapshot.reconstitute(next.entry, next.movements, next.version, AccountProjection.from(next))
            .getOrThrow()
        val cashout = additions.first().type == MovementType.FINAL_WITHDRAWAL
        checked(
            next.version == Math.addExact(expected, if (cashout) 1 else additions.size.toLong()),
            "Invalid projection version"
        )
        changed(
            jdbc.update(
                "UPDATE account_balance SET balance=?,accepted_stakes=?,refunded_stakes=?,winnings=?,version=?,cashed_out=? WHERE race_day_id=? AND player_id=? AND version=?",
                next.balance.amount,
                next.acceptedStakes.amount,
                next.refundedStakes.amount,
                next.winnings.amount,
                next.version,
                next.movements.any { it.type == MovementType.FINAL_WITHDRAWAL },
                day.value,
                player.value,
                expected
            )
        )
        additions.forEach { m ->
            jdbc.update(
                "INSERT INTO account_movement(race_day_id,player_id,movement_key,movement_type,amount,coupon_id,leg_ordinal,settlement_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                day.value,
                player.value,
                m.key,
                m.type.name,
                m.amount.amount,
                m.couponId?.value ?: m.legId?.couponId?.value,
                m.legId?.ordinal,
                m.settlementId?.value,
                timestamp(at)
            )
        }; Unit
    }
}
