package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.umamusume.tiket.core.betting.AtomicBet
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.identity.HorseId
import pl.umamusume.tiket.core.identity.PlayerId
import pl.umamusume.tiket.core.identity.RaceDayId
import pl.umamusume.tiket.core.identity.RaceId
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.validation.getOrThrow
import java.sql.ResultSet
import java.time.Instant
import java.util.*

class StaleVersionException : IllegalStateException("Persisted version changed or context does not exist")
class InvalidPersistenceException(message: String) : IllegalStateException(message)

internal fun checked(condition: Boolean, message: String) {
    if (!condition) throw InvalidPersistenceException(message)
}

internal fun changed(count: Int) {
    if (count != 1) throw StaleVersionException()
}

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
internal fun ResultSet.money(column: String) = Money(getLong(column))
internal fun ResultSet.atomic(): AtomicBet {
    val type = BetType.valueOf(getString("bet_type"))
    val horses = listOfNotNull(
        getObject("horse_1", UUID::class.java),
        getObject("horse_2", UUID::class.java),
        getObject("horse_3", UUID::class.java)
    ).map(::HorseId)
    val bet = AtomicBet.create(type, horses).getOrThrow()
    checked(bet.canonical == getString("canonical"), "Noncanonical persisted atomic selection")
    return bet
}

internal fun atomArgs(bet: AtomicBet): Array<Any?> =
    arrayOf(bet.canonical, bet.horses[0].value, bet.horses.getOrNull(1)?.value, bet.horses.getOrNull(2)?.value)

internal fun timestamp(at: Instant) = java.sql.Timestamp.from(at)
internal fun <T : Any> JdbcTemplate.one(sql: String, vararg args: Any?, mapper: (ResultSet) -> T): T? =
    query(sql, { rs, _ -> mapper(rs) }, *args).singleOrNull()

@org.springframework.stereotype.Component
class PersistenceTransactions(manager: PlatformTransactionManager,
    @org.springframework.beans.factory.annotation.Value("\${tiket.database.transaction-timeout-seconds:20}") timeoutSeconds: Int) {
    private val template = TransactionTemplate(manager).apply { require(timeoutSeconds in 1..300); timeout = timeoutSeconds }
    fun <T : Any> required(block: () -> T): T = template.execute { block() }
}

@Repository
class PersistenceLocks(private val jdbc: JdbcTemplate) {
    private fun active() {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) { "Locks require a transaction" }
    }

    fun day(id: RaceDayId) {
        active(); checked(
            jdbc.queryForList(
                "SELECT race_day_id FROM race_day_state WHERE race_day_id=? FOR UPDATE",
                id.value
            ).size == 1, "Missing day"
        )
    }

    fun race(day: RaceDayId, id: RaceId) {
        active(); checked(
            jdbc.queryForList(
                "SELECT s.race_id FROM race_state s JOIN race r ON r.id=s.race_id WHERE r.race_day_id=? AND r.id=? FOR UPDATE OF s",
                day.value,
                id.value
            ).size == 1, "Missing race"
        )
    }

    fun accounts(day: RaceDayId, players: Collection<PlayerId>) {
        active(); players.distinct().sortedBy { it.value.toString() }.forEach {
            checked(
                jdbc.queryForList(
                    "SELECT player_id FROM account_balance WHERE race_day_id=? AND player_id=? FOR UPDATE",
                    day.value,
                    it.value
                ).size == 1, "Missing account"
            )
        }
    }

    fun pools(day: RaceDayId, race: RaceId) {
        active(); checked(
            jdbc.queryForList(
                "SELECT s.bet_type FROM pool_state s JOIN race r ON r.id=s.race_id WHERE r.race_day_id=? AND r.id=? ORDER BY s.bet_type FOR UPDATE OF s",
                day.value,
                race.value
            ).size == 4, "Incomplete pools"
        )
    }
}
