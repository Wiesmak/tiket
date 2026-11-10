package pl.umamusume.tiket.backend.application

import org.springframework.stereotype.Service
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.raceday.RaceDayState
import pl.umamusume.tiket.core.validation.getOrThrow
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class AccountApplicationService(
    private val days: RaceDayApplicationService, private val entries: PlayerEntryRepository,
    private val accounts: AccountRepository, private val tx: PersistenceTransactions,
    private val locks: PersistenceLocks, private val commands: AdministrationCommands
) {
    private data class Context(val day: RaceDayId, val player: PlayerId, val state: RaceDayState,
                               val account: AccountSnapshot, val currency: String)
    private fun locked(r: AccountQuery): Context {
        val day = RaceDayId(uuid(r.raceDayId)); val player = PlayerId(uuid(r.playerId))
        val state = days.locked(day)
        if (persisted { entries.load(day, player) } == null) throw MissingRecord()
        locks.accounts(day, listOf(player))
        val account = persisted { accounts.load(day, player) } ?: throw MissingRecord()
        return Context(day, player, state, account, days.definition(day).currency)
    }
    private fun cashed(a: AccountSnapshot) = a.movements.any { it.type == MovementType.FINAL_WITHDRAWAL }
    private fun active(c: Context) {
        if (cashed(c.account)) throw CommandPrecondition("Account already finalized; retrieve receipt")
    }
    private fun information(c: Context): AccountInformation {
        val a = c.account
        return AccountInformation.newBuilder().setRaceDayId(c.day.value.toString()).setPlayerId(c.player.value.toString())
            .setCurrency(c.currency).setPhase(DayPhase.valueOf(c.state.phase.name))
            .setOpeningPaid(a.entry.openingPaid.amount).setOpeningPromotional(a.entry.openingFreebet.amount)
            .setAcceptedStakes(a.acceptedStakes.amount).setRefundedStakes(a.refundedStakes.amount)
            .setQualifyingTurnover(a.qualifyingTurnover.amount).setCreditedWinnings(a.winnings.amount)
            .setBalance(a.balance.amount).setAccountVersion(a.version).setCashedOut(cashed(a)).build()
    }
    fun get(r: AccountQuery): AccountInformation = tx.required { information(locked(r)) }
    fun quote(r: AccountQuery): WithdrawalQuoteInformation = tx.required {
        val c = locked(r); active(c)
        val q = WithdrawalPolicy.quote(c.account, c.state).getOrThrow()
        WithdrawalQuoteInformation.newBuilder().setAccount(information(c)).setUnlockedPromotional(q.unlocked.amount)
            .setWithdrawable(q.withdrawable.amount).setForfeitable(q.forfeitable.amount)
            .setRaceDayVersion(c.state.version).build()
    }
    fun cashOut(r: CashOutRequest): CashoutReceipt {
        val day = uuid(r.raceDayId); val player = uuid(r.playerId)
        require(r.expectedDayVersion >= 0 && r.expectedAccountVersion >= 0)
        return commands.execute("CashOut", "$day/$player", r.idempotencyKey, r, CashoutReceipt::parseFrom) {
            val c = locked(AccountQuery.newBuilder().setRaceDayId(r.raceDayId).setPlayerId(r.playerId).build())
            version(r.expectedDayVersion, c.state.version); version(r.expectedAccountVersion, c.account.version)
            active(c)
            WithdrawalPolicy.quote(c.account, c.state).getOrThrow()
            val next = AccountProjector.cashOut(c.account, c.state).getOrThrow()
            accounts.append(c.account.version, next, Instant.now().truncatedTo(ChronoUnit.MICROS))
            receipt(c.copy(account = persisted { accounts.load(c.day, c.player) }!!))
        }
    }
    private fun receipt(c: Context): CashoutReceipt {
        if (!cashed(c.account)) throw CommandPrecondition("Account has not been finalized")
        val terminal = accounts.history(c.day, c.player, 0, 3, terminalOnly = true)
        checked(terminal.size == 2 && terminal[0].kind == AccountMovementKind.FINAL_WITHDRAWAL &&
            terminal[1].kind == AccountMovementKind.PROMOTIONAL_FORFEITURE &&
            terminal[0].createdAt == terminal[1].createdAt, "Invalid terminal receipt")
        return CashoutReceipt.newBuilder().setRaceDayId(c.day.value.toString()).setPlayerId(c.player.value.toString())
            .setCurrency(c.currency).setWithdrawn(terminal[0].amount).setPromotionalForfeiture(terminal[1].amount)
            .setBalance(c.account.balance.amount).setAccountVersion(c.account.version)
            .setCashedOutAt(terminal[0].createdAt).setReceiptReference("${c.day.value}/${c.player.value}").build()
    }
    fun receipt(r: AccountQuery): CashoutReceipt = tx.required { receipt(locked(r)) }
    fun movements(r: AccountMovementsRequest): AccountMovements = tx.required {
        require(r.hasAccount() && r.pageSize in 1..100 && r.afterSequence >= 0)
        val c = locked(r.account)
        val rows = accounts.history(c.day, c.player, r.afterSequence, r.pageSize + 1)
        AccountMovements.newBuilder().addAllMovements(rows.take(r.pageSize))
            .apply { if (rows.size > r.pageSize) nextSequence = rows[r.pageSize - 1].sequence }.build()
    }
}
