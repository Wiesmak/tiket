package pl.umamusume.tiket.core.accounting

import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.money.moneySum
import pl.umamusume.tiket.core.raceday.RaceDayPhase
import pl.umamusume.tiket.core.raceday.RaceDayState
import pl.umamusume.tiket.core.validation.*
import java.math.BigInteger

data class PlayerEntry(val raceDayId: RaceDayId, val playerId: PlayerId, val openingPaid: Money, val openingFreebet: Money) {
    val openingBalance: Money = openingPaid + openingFreebet
}
enum class MovementType { COUPON_STAKE, LEG_REFUND, WINNINGS, FINAL_WITHDRAWAL, PROMOTIONAL_FORFEITURE }
data class AccountMovement(val key: String, val type: MovementType, val amount: Money,
                           val couponId: CouponId? = null, val legId: BetLegId? = null,
                           val settlementId: SettlementId? = null)
class AccountSnapshot internal constructor(val entry: PlayerEntry, movements: List<AccountMovement>,
    val balance: Money, val acceptedStakes: Money, val refundedStakes: Money, val winnings: Money, val version: Long) {
    val movements = frozen(movements)
    val qualifyingTurnover get() = acceptedStakes - refundedStakes
    companion object {
        fun opening(entry: PlayerEntry) = AccountSnapshot(entry, emptyList(), entry.openingBalance,
            Money.ZERO,Money.ZERO,Money.ZERO,0)

        /**
         * Replays ordered authoritative movements. openingVersion is the version of the frozen
         * opening-funding record (including pre-start funding edits). Cashout is one version step.
         */
        fun reconstitute(entry: PlayerEntry, movements: List<AccountMovement>, version: Long,
                         expectedProjection: AccountProjection? = null,
                         openingVersion: Long = 0): Decision<AccountSnapshot> = decide {
            ensure(openingVersion >= 0 && version >= 0, ErrorCode.INCONSISTENT_ACCOUNT, "negative account version")
            val history = frozen(movements)
            ensure(history.all { it.key.isNotBlank() } && history.map { it.key }.distinct().size == history.size,
                ErrorCode.DUPLICATE_OPERATION, "movement identities must be unique and nonblank")
            var account = AccountSnapshot(entry, emptyList(), entry.openingBalance,
                Money.ZERO, Money.ZERO, Money.ZERO, openingVersion)
            var index = 0
            while (index < history.size) {
                val movement = history[index]
                if (movement.type in setOf(MovementType.FINAL_WITHDRAWAL, MovementType.PROMOTIONAL_FORFEITURE)) {
                    ensure(index + 2 == history.size, ErrorCode.INCONSISTENT_ACCOUNT,
                        "cashout requires exactly two terminal movements")
                    val withdrawal = movement
                    val forfeiture = history[index + 1]
                    val quote = WithdrawalPolicy.hypothetical(account)
                    ensure(withdrawal.type == MovementType.FINAL_WITHDRAWAL &&
                        forfeiture.type == MovementType.PROMOTIONAL_FORFEITURE &&
                        withdrawal.amount == quote.withdrawable && forfeiture.amount == quote.forfeitable &&
                        listOf(withdrawal, forfeiture).all {
                            it.couponId == null && it.legId == null && it.settlementId == null
                        }, ErrorCode.INCONSISTENT_ACCOUNT, "invalid final withdrawal/forfeiture")
                    account = AccountSnapshot(entry, history, Money.ZERO, account.acceptedStakes,
                        account.refundedStakes, account.winnings, Math.incrementExact(account.version))
                    index += 2
                } else {
                    ensure(movement.couponId == null || movement.legId == null ||
                        movement.couponId == movement.legId.couponId,
                        ErrorCode.INCONSISTENT_ACCOUNT, "movement coupon/leg ownership mismatch")
                    when (movement.type) {
                        MovementType.COUPON_STAKE -> ensure(movement.amount.amount > 0 &&
                            movement.legId == null && movement.settlementId == null,
                            ErrorCode.INCONSISTENT_ACCOUNT, "invalid stake movement")
                        MovementType.LEG_REFUND -> ensure(movement.amount.amount > 0 && movement.settlementId == null,
                            ErrorCode.INCONSISTENT_ACCOUNT, "invalid refund movement")
                        else -> Unit
                    }
                    account = AccountProjector.apply(account, movement).getOrThrow()
                    index++
                }
            }
            ensure(account.version == version, ErrorCode.INCONSISTENT_ACCOUNT, "account version does not match replay")
            ensure(expectedProjection == null || expectedProjection == AccountProjection.from(account),
                ErrorCode.INCONSISTENT_ACCOUNT, "stored projection differs from movement replay")
            account
        }
    }
}
/** Optional stored projection used only for reconciliation; never a source of balances. */
data class AccountProjection(val balance: Money, val acceptedStakes: Money,
                             val refundedStakes: Money, val winnings: Money) {
    companion object {
        fun from(account: AccountSnapshot) = AccountProjection(account.balance, account.acceptedStakes,
            account.refundedStakes, account.winnings)
    }
}
object AccountProjector {
    fun apply(snapshot: AccountSnapshot, movement: AccountMovement): Decision<AccountSnapshot> = decide {
        ensure(movement.key.isNotBlank() && snapshot.movements.none { it.key == movement.key },
            ErrorCode.DUPLICATE_OPERATION, "movement key already applied")
        ensure(snapshot.movements.none { it.type in setOf(MovementType.FINAL_WITHDRAWAL, MovementType.PROMOTIONAL_FORFEITURE) },
            ErrorCode.INCONSISTENT_ACCOUNT, "account cashout has begun; use cashOut atomically")
        project(snapshot, movement)
    }
    private fun project(s: AccountSnapshot, m: AccountMovement): AccountSnapshot {
        var balance = s.balance
        var accepted = s.acceptedStakes
        var refunded = s.refundedStakes
        var winnings = s.winnings
        when(m.type) {
            MovementType.COUPON_STAKE -> {
                ensure(m.couponId != null && s.movements.none { it.type == m.type && it.couponId == m.couponId },
                    ErrorCode.DUPLICATE_OPERATION, "stake already applied")
                balance -= m.amount; accepted += m.amount
            }
            MovementType.LEG_REFUND -> {
                ensure(m.legId != null && s.movements.none { it.type == m.type && it.legId == m.legId },
                    ErrorCode.ALREADY_REFUNDED, "leg refund already applied")
                ensure(s.movements.none { it.type == MovementType.WINNINGS && it.legId == m.legId },
                    ErrorCode.INCONSISTENT_ACCOUNT, "settled leg cannot be refunded")
                val stake = s.movements.singleOrNull { it.type == MovementType.COUPON_STAKE && it.couponId == m.legId!!.couponId }
                val prior = moneySum(s.movements.filter { it.type == MovementType.LEG_REFUND &&
                    it.legId?.couponId == m.legId!!.couponId }.map { it.amount })
                ensure(stake != null && prior + m.amount <= stake.amount, ErrorCode.INCONSISTENT_ACCOUNT, "refund exceeds purchased stake")
                balance += m.amount; refunded += m.amount
            }
            MovementType.WINNINGS -> {
                ensure(m.legId != null && m.settlementId != null &&
                    s.movements.none { it.type == MovementType.WINNINGS && it.legId == m.legId },
                    ErrorCode.DUPLICATE_OPERATION, "winnings already credited")
                ensure(s.movements.any { it.type == MovementType.COUPON_STAKE && it.couponId == m.legId!!.couponId } &&
                    s.movements.none { it.type == MovementType.LEG_REFUND && it.legId == m.legId },
                    ErrorCode.INCONSISTENT_ACCOUNT, "winning leg not active")
                balance += m.amount; winnings += m.amount
            }
            MovementType.FINAL_WITHDRAWAL, MovementType.PROMOTIONAL_FORFEITURE ->
                reject(ErrorCode.WITHDRAWAL_PROHIBITED, "use cashOut")
        }
        return AccountSnapshot(s.entry,s.movements + m,balance,accepted,refunded,winnings,Math.incrementExact(s.version))
    }
    fun cashOut(snapshot: AccountSnapshot, day: RaceDayState): Decision<AccountSnapshot> = decide {
        val quote = WithdrawalPolicy.quote(snapshot,day).getOrThrow()
        ensure(snapshot.movements.none { it.type in setOf(MovementType.FINAL_WITHDRAWAL,MovementType.PROMOTIONAL_FORFEITURE) },
            ErrorCode.DUPLICATE_OPERATION, "already cashed out")
        ensure(snapshot.movements.none { it.key == "withdrawal" || it.key == "forfeiture" },
            ErrorCode.DUPLICATE_OPERATION, "cashout movement identity already used")
        val movements = listOf(
            AccountMovement("withdrawal",MovementType.FINAL_WITHDRAWAL,quote.withdrawable),
            AccountMovement("forfeiture",MovementType.PROMOTIONAL_FORFEITURE,quote.forfeitable))
        AccountSnapshot(snapshot.entry,snapshot.movements + movements,Money.ZERO,snapshot.acceptedStakes,
            snapshot.refundedStakes,snapshot.winnings,Math.incrementExact(snapshot.version))
    }
}
data class WithdrawalQuote(val balance: Money, val unlocked: Money, val withdrawable: Money, val forfeitable: Money)
/** Single-balance promotional eligibility. Refunded stakes do not unlock promotion.
 * A quote reserves nothing; cashout must be committed atomically and does not disburse money.
 */
object WithdrawalPolicy {
    fun hypothetical(account: AccountSnapshot): WithdrawalQuote {
        val unlocked = minOf(account.entry.openingFreebet, account.qualifyingTurnover)
        val limit = BigInteger.valueOf(account.entry.openingPaid.amount).add(BigInteger.valueOf(unlocked.amount))
            .add(BigInteger.valueOf(account.winnings.amount))
        val allowed = Money(limit.min(BigInteger.valueOf(account.balance.amount)).longValueExact())
        return WithdrawalQuote(account.balance,unlocked,allowed,account.balance - allowed)
    }
    fun quote(account: AccountSnapshot, day: RaceDayState): Decision<WithdrawalQuote> = decide {
        ensure(day.phase == RaceDayPhase.COMPLETED, ErrorCode.WITHDRAWAL_PROHIBITED, "day not completed")
        hypothetical(account)
    }
}
