package pl.umamusume.tiket.backend.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.raceday.EnrollmentPolicy
import pl.umamusume.tiket.core.validation.getOrThrow

@Service
class EnrollmentApplicationService(
    private val days: RaceDayApplicationService, private val entries: PlayerEntryRepository,
    private val accounts: AccountRepository, private val tx: PersistenceTransactions,
    private val locks: PersistenceLocks, private val commands: AdministrationCommands, private val jdbc: JdbcTemplate
) {
    private fun information(day: RaceDayId, player: PlayerId): EnrollmentInformation {
        val a = persisted { accounts.load(day, player) } ?: throw MissingRecord()
        val openingVersion = jdbc.queryForObject("SELECT opening_version FROM account_balance WHERE race_day_id=? AND player_id=?",
            Long::class.java, day.value, player.value)!!
        return EnrollmentInformation.newBuilder().setRaceDayId(day.value.toString()).setPlayerId(player.value.toString())
            .setOpeningPaid(a.entry.openingPaid.amount).setOpeningPromotional(a.entry.openingFreebet.amount)
            .setBalance(a.balance.amount).setAccountVersion(a.version).setOpeningVersion(openingVersion).build()
    }
    fun enroll(request: EnrollPlayerRequest): EnrollmentInformation {
        val day = RaceDayId(uuid(request.raceDayId))
        val player = PlayerId(uuid(request.playerId))
        val entry = PlayerEntry(day, player, Money(request.openingPaid), Money(request.openingPromotional))
        require(request.expectedDayVersion >= 0)
        return commands.execute("EnrollPlayer", day.value.toString()+"/"+player.value, request.idempotencyKey,
            request, EnrollmentInformation::parseFrom) {
            val state = days.locked(day)
            version(request.expectedDayVersion, state.version)
            EnrollmentPolicy.enroll(days.definition(day), state, persisted { entries.list(day) }, entry).getOrThrow()
            entries.insert(entry)
            information(day, player)
        }
    }
    fun fund(request: AddOpeningFundingRequest): EnrollmentInformation {
        val day = RaceDayId(uuid(request.raceDayId))
        val player = PlayerId(uuid(request.playerId))
        val paid = Money(request.additionalPaid)
        val promotional = Money(request.additionalPromotional)
        require(paid.amount > 0 || promotional.amount > 0) { "Positive funding required" }
        require(request.expectedAccountVersion >= 0)
        return commands.execute("AddOpeningFunding", day.value.toString()+"/"+player.value, request.idempotencyKey,
            request, EnrollmentInformation::parseFrom) {
            val state = days.locked(day)
            if (persisted { entries.load(day, player) } == null) throw MissingRecord()
            locks.accounts(day, listOf(player))
            val account = persisted { accounts.load(day, player) } ?: throw MissingRecord()
            version(request.expectedAccountVersion, account.version)
            if (jdbc.queryForObject("SELECT count(*) FROM coupon WHERE race_day_id=? AND player_id=?", Long::class.java,
                    day.value, player.value) != 0L) throw CommandPrecondition("Account has wagers")
            val next = EnrollmentPolicy.addOpeningFunding(state, account, paid, promotional).getOrThrow()
            accounts.addOpeningFunding(account.version, next, paid.amount, promotional.amount)
            information(day, player)
        }
    }
    fun get(request: EnrollmentQuery): EnrollmentInformation = tx.required {
        val day = RaceDayId(uuid(request.raceDayId))
        val player = PlayerId(uuid(request.playerId))
        days.locked(day)
        if (persisted { entries.load(day, player) } == null) throw MissingRecord()
        locks.accounts(day, listOf(player))
        information(day, player)
    }
    fun list(request: DayQuery): PlayerList = tx.required {
        val day = RaceDayId(uuid(request.raceDayId))
        days.locked(day)
        val players = persisted { entries.list(day) }
        locks.accounts(day, players.map { it.playerId })
        PlayerList.newBuilder().addAllPlayers(players.map { information(day, it.playerId) }).build()
    }
}
