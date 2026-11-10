package pl.umamusume.tiket.core

import io.kotest.core.spec.style.FunSpec
import kotlin.test.*
import kotlin.random.Random
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

class AdditionalInvariantsTest : FunSpec({
    test("enrollment and deposits produce snapshots and freeze at start") {
        val f = Fixture()
        val initial = EnrollmentPolicy.enroll(f.day,RaceDayState(),emptyList(),f.entry).ok()
        val funded = EnrollmentPolicy.addOpeningFunding(RaceDayState(RaceDayPhase.READY),initial,Money(20),Money(5)).ok()
        assertEquals(Money(1125),funded.balance)
        assertEquals(1L,funded.version)
        assertEquals(Money(1100),initial.balance)
        rejected(ErrorCode.INVALID_TRANSITION,EnrollmentPolicy.enroll(f.day,f.running,emptyList(),f.entry))
        rejected(ErrorCode.INVALID_TRANSITION,EnrollmentPolicy.addOpeningFunding(f.running,initial,Money(1)))
        rejected(ErrorCode.DUPLICATE_OPERATION,EnrollmentPolicy.enroll(f.day,RaceDayState(),listOf(f.entry),f.entry))
    }
    test("configuration permits independently staged simultaneous races") {
        val f = Fixture()
        val day = RaceDayDefinition(f.day.id,f.day.date,f.day.currency,f.day.races,f.day.rulesVersion,
            f.day.pools,RaceDayRules(maximumOpenRaces=2), f.day.displayNumber, f.day.timeZone)
        val initial = listOf(RaceState(f.race.id),RaceState(f.second.id))
        val first = RaceDayLifecyclePolicy.openBetting(day,f.running,f.race.id,initial).ok()
        val second = RaceDayLifecyclePolicy.openBetting(day,f.running,f.second.id,listOf(first,initial[1])).ok()
        assertEquals(RacePhase.BETTING_OPEN,second.phase)
        assertEquals(RacePhase.SCHEDULED,initial[1].phase)
    }
    test("scratch invalidates proposed result and cannot change verified results") {
        val f = Fixture()
        val proposed = RaceResult(f.race.id,"r1",listOf(horse(1),horse(2),horse(3)),ResultStatus.PROPOSED)
        val state = RaceState(f.race.id,RacePhase.RESULT_PROPOSED,result=proposed)
        val scratch = RefundPlanner.plan(f.race,state,RefundReason.Scratch(horse(5)),emptyList()).ok()
        assertEquals(RacePhase.BETTING_CLOSED,scratch.resultingState.phase)
        assertNull(scratch.resultingState.result)
        rejected(ErrorCode.INVALID_TRANSITION,RefundPlanner.plan(f.race,
            RaceState(f.race.id,RacePhase.RESULT_VERIFIED,result=proposed),RefundReason.Scratch(horse(5)),emptyList()))
    }
    test("fixed-seed refund patterns conserve complete historical pools") {
        val random = Random(82187)
        repeat(80) {
            val f = Fixture(stake=random.nextLong(1,5),take=random.nextInt(10001))
            val coupons = BetType.entries.map { type ->
                f.purchase(type,CouponSelection.Box(f.race.roster.toList()),random.nextLong(1,5)).coupon
            }
            var state = f.open
            val existing = linkedMapOf<BetLegId,LegResolution>()
            val scratched = listOf(4,5).shuffled(random).take(random.nextInt(3))
            for(n in scratched) {
                val plan = RefundPlanner.plan(f.race,state,RefundReason.Scratch(horse(n)),coupons,existing).ok()
                existing.putAll(plan.resolutions.associateBy { it.legId })
                state = plan.resultingState
                assertTrue(plan.resolutions.all { r ->
                    coupons.flatMap { it.legs }.single { it.id == r.legId }.bet.horses.contains(horse(n))
                })
            }
            val result = RaceResult(f.race.id,"r1",listOf(horse(1),horse(2),horse(3)),ResultStatus.VERIFIED)
            state = RaceState(f.race.id,RacePhase.RESULT_VERIFIED,state.scratched,result,state.version)
            val pools = BetType.entries.associate { type ->
                val key = PoolKey(f.race.id,type)
                key to PoolSnapshot.aggregate(key,coupons,existing).ok()
            }
            val rules = BetType.entries.associateWith { f.day.pools.getValue(PoolKey(f.race.id,it)) }
            val plan = RaceSettlementPlanner.plan(SettlementId(uuid(1000)),f.race,state,rules,pools,coupons,existing).ok()
            val again = RaceSettlementPlanner.plan(plan.id,f.race,state,rules,pools,coupons,existing).ok()
            assertEquals(plan.pools,again.pools)
            assertEquals(plan.resolutions,again.resolutions)
            assertEquals(plan.credits,again.credits)
            val original = Money(coupons.sumOf { it.totalStake.amount })
            val refunded = Money(existing.values.sumOf { it.amount.amount })
            val payouts = Money(plan.playerPayouts.values.sumOf { it.amount })
            val house = Money(plan.pools.values.sumOf { it.houseAllocation.amount })
            assertEquals(original,refunded+payouts+house)
            assertEquals(coupons.sumOf { it.legs.size },existing.size+plan.resolutions.size)
        }
    }
    test("settlement requires verified sufficient results and reconciled history") {
        val f = Fixture()
        val coupons = listOf(f.purchase(BetType.WIN,positional(1)).coupon)
        val rules = BetType.entries.associateWith { f.day.pools.getValue(PoolKey(f.race.id,it)) }
        val pools = BetType.entries.associate {
            val key = PoolKey(f.race.id,it)
            key to PoolSnapshot.aggregate(key,coupons).ok()
        }
        val id = SettlementId(uuid(1000))
        rejected(ErrorCode.RESULT_NOT_VERIFIED,RaceSettlementPlanner.plan(id,f.race,f.open,rules,pools,coupons))
        val result = RaceResult(f.race.id,"short",listOf(horse(1)),ResultStatus.VERIFIED)
        rejected(ErrorCode.INVALID_RESULT,RaceSettlementPlanner.plan(id,f.race,
            RaceState(f.race.id,RacePhase.RESULT_VERIFIED,result=result),rules,pools,coupons))
        val leg = coupons.single().legs.single()
        val normal = RaceState(f.race.id,RacePhase.RESULT_VERIFIED,result=
            RaceResult(f.race.id,"normal",listOf(horse(1),horse(2),horse(3)),ResultStatus.VERIFIED))
        rejected(ErrorCode.INCONSISTENT_POOL,RaceSettlementPlanner.plan(id,f.race,normal,rules,pools,coupons,
            mapOf(leg.id to LegResolution(leg.id,LegOutcome.REFUNDED,Money(9)))))
    }
})
