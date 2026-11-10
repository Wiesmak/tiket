package pl.umamusume.tiket.core

import io.kotest.core.spec.style.FunSpec
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.identity.BetLegId
import pl.umamusume.tiket.core.identity.CouponId
import pl.umamusume.tiket.core.identity.SettlementId
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.money.TakeRate
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.raceday.RaceDayLifecyclePolicy
import pl.umamusume.tiket.core.raceday.RaceDayPhase
import pl.umamusume.tiket.core.raceday.RaceDayState
import pl.umamusume.tiket.core.settlement.RefundPlanner
import pl.umamusume.tiket.core.settlement.RefundReason
import pl.umamusume.tiket.core.validation.DomainException
import pl.umamusume.tiket.core.validation.ErrorCode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountsLifecycleTest : FunSpec({
    fun account(paid: Long, free: Long, stakes: Long = 0, refunds: Long = 0, wins: Long = 0): AccountSnapshot {
        val f = Fixture()
        var a = AccountSnapshot.opening(PlayerEntry(f.day.id,f.entry.playerId,Money(paid),Money(free)))
        val coupon = CouponId(uuid(100))
        if(stakes > 0) a = AccountProjector.apply(a,AccountMovement("stake",MovementType.COUPON_STAKE,Money(stakes),coupon)).ok()
        if(refunds > 0) a = AccountProjector.apply(a,AccountMovement("refund",MovementType.LEG_REFUND,Money(refunds),coupon,BetLegId(coupon,0))).ok()
        if(wins > 0) a = AccountProjector.apply(a,AccountMovement("win",MovementType.WINNINGS,Money(wins),coupon,
            BetLegId(coupon,1),SettlementId(uuid(200)))).ok()
        return a
    }
    test("all mandatory promotional examples") {
        val examples = listOf(
            listOf(0L,100,0,0,0,100,0,100),
            listOf(0L,100,30,0,0,70,30,40),
            listOf(0L,100,30,0,150,220,180,40),
            listOf(100L,100,0,0,0,200,100,100),
            listOf(0L,100,60,20,0,60,40,20))
        for(e in examples) {
            val a = account(e[0],e[1],e[2],e[3],e[4])
            val q = WithdrawalPolicy.hypothetical(a)
            assertEquals(Money(e[5]),q.balance)
            assertEquals(Money(e[6]),q.withdrawable)
            assertEquals(Money(e[7]),q.forfeitable)
        }
    }
    test("fully unlocked, zero balance and extreme valid amounts") {
        assertEquals(Money.ZERO,WithdrawalPolicy.hypothetical(account(0,100,100)).withdrawable)
        assertEquals(Money(200),WithdrawalPolicy.hypothetical(account(100,100,100,0,100)).withdrawable)
        assertEquals(Money(Long.MAX_VALUE),WithdrawalPolicy.hypothetical(account(Long.MAX_VALUE,0)).withdrawable)
        assertFailsWith<ArithmeticException> { account(Long.MAX_VALUE,1) }
        assertFailsWith<DomainException> { Money(-1) }
        assertFailsWith<DomainException> { TakeRate(10001) }
    }
    test("movement replay, over-refund, winnings replay and overdraft rejected") {
        val f = Fixture()
        val c = CouponId(uuid(100))
        val stake = AccountMovement("s",MovementType.COUPON_STAKE,Money(50),c)
        var a = AccountProjector.apply(f.account,stake).ok()
        rejected(ErrorCode.DUPLICATE_OPERATION,AccountProjector.apply(a,stake))
        rejected(ErrorCode.DUPLICATE_OPERATION,AccountProjector.apply(a,stake.copy(key="other")))
        val refund = AccountMovement("r",MovementType.LEG_REFUND,Money(20),c,BetLegId(c,0))
        a = AccountProjector.apply(a,refund).ok()
        rejected(ErrorCode.ALREADY_REFUNDED,AccountProjector.apply(a,refund.copy(key="r2")))
        rejected(ErrorCode.INCONSISTENT_ACCOUNT,AccountProjector.apply(a,refund.copy(key="r3",amount=Money(31),legId=BetLegId(c,1))))
        val win = AccountMovement("w",MovementType.WINNINGS,Money(100),c,BetLegId(c,1),SettlementId(uuid(200)))
        a = AccountProjector.apply(a,win).ok()
        rejected(ErrorCode.DUPLICATE_OPERATION,AccountProjector.apply(a,win.copy(key="w2")))
        rejected(ErrorCode.INSUFFICIENT_BALANCE,AccountProjector.apply(a,stake.copy(key="s2",couponId=CouponId(uuid(101)),amount=Money(10000))))
        rejected(ErrorCode.WITHDRAWAL_PROHIBITED,WithdrawalPolicy.quote(a,f.running))
        val out = AccountProjector.cashOut(a,RaceDayState(RaceDayPhase.COMPLETED)).ok()
        assertEquals(Money.ZERO,out.balance)
        rejected(ErrorCode.DUPLICATE_OPERATION,AccountProjector.cashOut(out,RaceDayState(RaceDayPhase.COMPLETED)))
    }
    test("fixed seed wager refund withdrawal properties") {
        val random = Random(9521)
        repeat(300) {
            val paid = random.nextLong(1000)
            val free = random.nextLong(1000)
            val stake = random.nextLong(paid+free+1)
            val refund = random.nextLong(stake+1)
            val wins = if(stake > 0) random.nextLong(1000) else 0
            val a = account(paid,free,stake,refund,wins)
            val q = WithdrawalPolicy.hypothetical(a)
            assertEquals(Money(stake-refund),a.qualifyingTurnover)
            assertTrue(q.withdrawable <= a.balance)
            assertEquals(a.balance,q.withdrawable+q.forfeitable)
            assertEquals(Money(paid+free-stake+refund+wins),a.balance)
            assertEquals(q,WithdrawalPolicy.hypothetical(a))
        }
    }
    test("event transitions freeze enrollment and enforce one open race") {
        val f = Fixture()
        val initial = listOf(RaceState(f.race.id),RaceState(f.second.id))
        var day = RaceDayState()
        assertTrue(RaceDayLifecyclePolicy.definitionChangesAllowed(day))
        day = RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.READY,initial).ok()
        assertTrue(RaceDayLifecyclePolicy.enrollmentAllowed(day))
        assertFalse(RaceDayLifecyclePolicy.definitionChangesAllowed(day))
        day = RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.RUNNING,initial).ok()
        assertFalse(RaceDayLifecyclePolicy.enrollmentAllowed(day)); assertFalse(RaceDayLifecyclePolicy.depositAllowed(day))
        val first = RaceDayLifecyclePolicy.openBetting(f.day,day,f.race.id,initial).ok()
        rejected(ErrorCode.INVALID_TRANSITION,RaceDayLifecyclePolicy.openBetting(f.day,day,f.second.id,listOf(first,initial[1])))
        rejected(ErrorCode.INVALID_TRANSITION,RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.COMPLETED,initial))
        rejected(ErrorCode.INVALID_TRANSITION,RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.FINALIZING,listOf(first,initial[1])))
        day = RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.FINALIZING,initial).ok()
        rejected(ErrorCode.INVALID_TRANSITION,RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.COMPLETED,initial))
        val terminal = listOf(RaceState(f.race.id,RacePhase.SETTLED),RaceState(f.second.id,RacePhase.VOIDED))
        day = RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.COMPLETED,terminal).ok()
        rejected(ErrorCode.INVALID_TRANSITION,RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.CLOSED,terminal))
        assertEquals(RaceDayPhase.CLOSED,RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.CLOSED,terminal,accountsCashedOut=true).ok().phase)
    }
    test("race result proposal correction verification and terminal protection") {
        val f = Fixture()
        var state = RaceLifecyclePolicy.transition(f.race,RaceState(f.race.id),RacePhase.BETTING_OPEN).ok()
        state = RaceLifecyclePolicy.transition(f.race,state,RacePhase.BETTING_CLOSED).ok()
        val result = RaceResult(f.race.id,"r1",listOf(horse(1),horse(2),horse(3)),ResultStatus.PROPOSED)
        state = RaceLifecyclePolicy.transition(f.race,state,RacePhase.RESULT_PROPOSED,result).ok()
        val corrected = RaceResult(f.race.id,"r2",listOf(horse(2),horse(1),horse(3)),ResultStatus.PROPOSED)
        state = RaceLifecyclePolicy.transition(f.race,state,RacePhase.RESULT_PROPOSED,corrected).ok()
        val verified = RaceResult(f.race.id,"r2",corrected.finishers,ResultStatus.VERIFIED)
        state = RaceLifecyclePolicy.transition(f.race,state,RacePhase.RESULT_VERIFIED,verified).ok()
        rejected(ErrorCode.INVALID_TRANSITION,RaceLifecyclePolicy.transition(f.race,state,RacePhase.RESULT_PROPOSED,result))
        rejected(ErrorCode.INVALID_TRANSITION,RefundPlanner.plan(f.race,state,RefundReason.VoidRace,emptyList()))
        rejected(ErrorCode.ALREADY_SETTLED,RaceLifecyclePolicy.transition(f.race,RaceState(f.race.id,RacePhase.SETTLED),RacePhase.BETTING_OPEN))
        for(finishers in listOf(listOf(horse(1),horse(1)),listOf(horse(99)))) rejected(ErrorCode.INVALID_RESULT,
            ResultValidator.validate(f.race,f.open,RaceResult(f.race.id,"bad",finishers,ResultStatus.PROPOSED)))
    }
})
