package pl.umamusume.tiket.core.raceday

import pl.umamusume.tiket.core.accounting.PlayerEntry
import pl.umamusume.tiket.core.accounting.AccountSnapshot
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.validation.*

/** Opening funding is replaced only before start; there is no in-event deposit movement. */
object EnrollmentPolicy {
    fun enroll(day: RaceDayDefinition, state: RaceDayState, existing: List<PlayerEntry>,
               entry: PlayerEntry): Decision<AccountSnapshot> = decide {
        ensure(RaceDayLifecyclePolicy.enrollmentAllowed(state), ErrorCode.INVALID_TRANSITION, "enrollment frozen")
        ensure(entry.raceDayId == day.id && existing.all { it.raceDayId == day.id },
            ErrorCode.INVALID_DEFINITION, "enrollment belongs to another event")
        ensure(existing.none { it.playerId == entry.playerId }, ErrorCode.DUPLICATE_OPERATION, "player already enrolled")
        AccountSnapshot.opening(entry)
    }
    fun addOpeningFunding(state: RaceDayState, account: AccountSnapshot, paid: Money,
                          promotional: Money = Money.ZERO): Decision<AccountSnapshot> = decide {
        ensure(RaceDayLifecyclePolicy.depositAllowed(state), ErrorCode.INVALID_TRANSITION, "opening funding frozen")
        ensure(account.movements.isEmpty(), ErrorCode.INCONSISTENT_ACCOUNT, "opening funding cannot change after activity")
        val e = account.entry
        AccountSnapshot(e.copy(openingPaid = e.openingPaid + paid, openingFreebet = e.openingFreebet + promotional),
            emptyList(), account.balance + paid + promotional, Money.ZERO, Money.ZERO, Money.ZERO,
            Math.incrementExact(account.version))
    }
}
