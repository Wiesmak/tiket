package pl.umamusume.tiket.core

import io.kotest.core.spec.style.FunSpec
import kotlin.test.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.*
import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.coupon.*
import pl.umamusume.tiket.core.pool.*
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.raceday.*
import pl.umamusume.tiket.core.settlement.*
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.validation.*

class SettlementIntegrationTest : FunSpec({
    test("partial wall and box refunds preserve purchase, pool and turnover") {
        val f = Fixture()
        val wall = f.purchase(BetType.EXACTA,positional(1,0),2).coupon
        val box = f.purchase(BetType.QUINELLA,box(1,2,3)).coupon
        val coupons = listOf(wall,box)
        val plan = RefundPlanner.plan(f.race,f.open,RefundReason.Scratch(horse(3)),coupons).ok()
        assertEquals(3,plan.resolutions.size)
        assertEquals(Money(40),plan.playerTotals[f.entry.playerId])
        assertEquals(Money(80),wall.totalStake)
        assertEquals(4,wall.legs.size)
        val existing = plan.resolutions.associateBy { it.legId }
        val repeat = RefundPlanner.plan(f.race,plan.resultingState,RefundReason.Scratch(horse(3)),coupons,existing).ok()
        assertTrue(repeat.resolutions.isEmpty()); assertEquals(plan.resultingState.version,repeat.resultingState.version)
        val remaining = PoolSnapshot.aggregate(PoolKey(f.race.id,BetType.EXACTA),coupons,existing).ok()
        assertEquals(Money(60),remaining.validStake)
        assertEquals(6,remaining.units.values.sum())
        val all = RefundPlanner.plan(f.race,plan.resultingState,RefundReason.Scratch(horse(1)),coupons,existing).ok()
        assertEquals(4,all.resolutions.size)
        val void = RefundPlanner.plan(f.race,plan.resultingState,RefundReason.VoidRace,coupons,existing).ok()
        assertEquals(Money(70),void.playerTotals[f.entry.playerId])
        assertEquals(RacePhase.VOIDED,void.resultingState.phase)
        var account = f.account
        for(c in coupons) account = AccountProjector.apply(account,
            AccountMovement("s:${c.id}",MovementType.COUPON_STAKE,c.totalStake,c.id)).ok()
        for(credit in plan.credits) account = AccountProjector.apply(account,credit.movement).ok()
        assertEquals(Money(70),account.qualifyingTurnover)
    }
    test("pool aggregation canonicalizes and rejects inconsistent projections") {
        val f = Fixture()
        val c1 = f.purchase(BetType.QUINELLA,positional(1,2),2).coupon
        val c2 = f.purchase(BetType.QUINELLA,positional(2,1),3).coupon
        val key = PoolKey(f.race.id,BetType.QUINELLA)
        val pool = PoolSnapshot.aggregate(key,listOf(c1,c2)).ok()
        assertEquals(1,pool.units.size); assertEquals(5,pool.units.values.single())
        assertEquals(Money(50),pool.validStake)
        val rules = f.day.pools.getValue(key)
        rejected(ErrorCode.INCONSISTENT_POOL,PoolSettlementCalculator.calculate(
            PoolSnapshot(key,pool.units,Money(51)),rules,AtomicBet.Quinella(horse(1),horse(2))))
        rejected(ErrorCode.INCONSISTENT_POOL,PoolSnapshot.aggregate(key,listOf(c1,c1)))
        val empty = PoolSnapshot.aggregate(PoolKey(f.second.id,BetType.QUINELLA),listOf(c1,c2)).ok()
        assertEquals(Money.ZERO,empty.validStake)
    }
    test("complete two-race domain scenario with multiple players all pools refunds cashouts") {
        val f = Fixture()
        val scheduled = listOf(RaceState(f.race.id),RaceState(f.second.id))
        var day = RaceDayLifecyclePolicy.transition(f.day,RaceDayState(),RaceDayPhase.READY,scheduled).ok()
        val other = PlayerEntry(f.day.id,PlayerId(uuid(31)),Money(300),Money(200))
        val accounts = linkedMapOf(f.entry.playerId to AccountSnapshot.opening(f.entry),other.playerId to AccountSnapshot.opening(other))
        day = RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.RUNNING,scheduled).ok()
        var state = RaceDayLifecyclePolicy.openBetting(f.day,day,f.race.id,scheduled).ok()
        val coupons = mutableListOf<Coupon>()
        fun buy(player: PlayerEntry, type: BetType, selection: CouponSelection, units: Long = 1) {
            val plan = BetPlacementPolicy.purchase(f.day,day,f.race,state,player,accounts.getValue(player.playerId),
                type,selection,units,CouponId(uuid(100+coupons.size)),java.time.Instant.EPOCH).ok()
            coupons += plan.coupon
            accounts[player.playerId] = AccountProjector.apply(accounts.getValue(player.playerId),plan.debit).ok()
        }
        buy(f.entry,BetType.WIN,positional(1),2)
        buy(other,BetType.WIN,positional(1),1)
        buy(f.entry,BetType.EXACTA,positional(1,0),2)
        buy(other,BetType.QUINELLA,box(1,2,5))
        buy(other,BetType.TRIFECTA,box(1,2,3,5))
        val accepted = Money(coupons.sumOf { it.totalStake.amount })
        val refund = RefundPlanner.plan(f.race,state,RefundReason.Scratch(horse(5)),coupons).ok()
        state = refund.resultingState
        val resolutions = refund.resolutions.associateBy { it.legId }
        for(credit in refund.credits) accounts[credit.playerId] =
            AccountProjector.apply(accounts.getValue(credit.playerId),credit.movement).ok()
        state = RaceLifecyclePolicy.transition(f.race,state,RacePhase.BETTING_CLOSED).ok()
        val proposed = RaceResult(f.race.id,"official-1",listOf(horse(1),horse(2),horse(3),horse(4)),ResultStatus.PROPOSED)
        state = RaceLifecyclePolicy.transition(f.race,state,RacePhase.RESULT_PROPOSED,proposed).ok()
        state = RaceLifecyclePolicy.transition(f.race,state,RacePhase.RESULT_VERIFIED,
            RaceResult(f.race.id,proposed.revision,proposed.finishers,ResultStatus.VERIFIED)).ok()
        val rules = BetType.entries.associateWith { f.day.pools.getValue(PoolKey(f.race.id,it)) }
        val snapshots = BetType.entries.associate { type ->
            val key = PoolKey(f.race.id,type)
            key to PoolSnapshot.aggregate(key,coupons,resolutions,7).ok()
        }
        val settlement = RaceSettlementPlanner.plan(SettlementId(uuid(500)),f.race,state,rules,snapshots,coupons,resolutions).ok()
        assertEquals(4,settlement.pools.size)
        assertEquals(coupons.sumOf { it.legs.size }-resolutions.size,settlement.resolutions.size)
        assertEquals(3,settlement.pools.getValue(PoolKey(f.race.id,BetType.WIN)).winningUnits)
        for(credit in settlement.credits) accounts[credit.playerId] =
            AccountProjector.apply(accounts.getValue(credit.playerId),credit.movement).ok()
        val paid = Money(settlement.playerPayouts.values.sumOf { it.amount })
        val house = Money(settlement.pools.values.sumOf { it.houseAllocation.amount })
        val refunded = Money(refund.playerTotals.values.sumOf { it.amount })
        FinancialReconciliation.validate(accepted,refunded,paid,house).ok()
        for(account in accounts.values) {
            assertEquals(account.entry.openingBalance-account.acceptedStakes+account.refundedStakes+account.winnings,account.balance)
        }
        rejected(ErrorCode.ALREADY_SETTLED,RaceSettlementPlanner.plan(SettlementId(uuid(501)),f.race,
            settlement.resultingState,rules,snapshots,coupons,resolutions))
        val broken = snapshots.toMutableMap()
        val key = PoolKey(f.race.id,BetType.WIN)
        broken[key] = PoolSnapshot(key,emptyMap(),Money.ZERO)
        rejected(ErrorCode.INCONSISTENT_POOL,RaceSettlementPlanner.plan(SettlementId(uuid(502)),f.race,state,rules,broken,coupons,resolutions))
        val secondVoid = RefundPlanner.plan(f.second,scheduled[1],RefundReason.VoidRace,emptyList()).ok()
        val terminal = listOf(settlement.resultingState,secondVoid.resultingState)
        day = RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.FINALIZING,terminal).ok()
        day = RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.COMPLETED,terminal).ok()
        var withdrawals = Money.ZERO
        var forfeitures = Money.ZERO
        for((player,account) in accounts.toMap()) {
            val quote = WithdrawalPolicy.quote(account,day).ok()
            withdrawals += quote.withdrawable; forfeitures += quote.forfeitable
            accounts[player] = AccountProjector.cashOut(account,day).ok()
            assertEquals(Money.ZERO,accounts.getValue(player).balance)
        }
        val funding = f.entry.openingBalance + other.openingBalance
        assertEquals(funding,withdrawals+forfeitures+house)
        assertEquals(RaceDayPhase.CLOSED,RaceDayLifecyclePolicy.transition(f.day,day,RaceDayPhase.CLOSED,
            terminal,accountsCashedOut=true).ok().phase)
    }
})
