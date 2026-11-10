package pl.umamusume.tiket.core

import io.kotest.core.spec.style.FunSpec
import kotlin.test.*
import kotlin.random.Random
import java.util.UUID
import java.time.Instant
import java.time.LocalDate
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

internal fun uuid(n: Int) = UUID(0,n.toLong())
internal fun horse(n: Int) = HorseId(uuid(n))
internal fun positional(vararg choices: Int) = CouponSelection.Positional(choices.map {
    if(it == 0) PositionChoice.Wall else PositionChoice.Horse(horse(it))
})
internal fun box(vararg choices: Int) = CouponSelection.Box(choices.map(::horse))
internal fun <T> Decision<T>.ok() = getOrThrow()
internal fun rejected(code: ErrorCode, decision: Decision<*>) {
    assertIs<Decision.Rejected>(decision)
    assertEquals(code,decision.error.code)
}
internal class Fixture(stake: Long = 10, take: Int = 1500) {
    val race = RaceDefinition(RaceId(uuid(10)),1,"First",(1..5).map { RaceRunner(horse(it),"$it","Horse $it") })
    val second = RaceDefinition(RaceId(uuid(11)),2,"Second",race.runners)
    val day = RaceDayDefinition(RaceDayId(uuid(20)),LocalDate.of(2026,11,10),"PLN",listOf(race,second),"v1",
        listOf(race,second).flatMap { r -> BetType.entries.map { PoolKey(r.id,it) to PoolRules(Money(stake),TakeRate(take)) } }.toMap())
    val running = RaceDayState(RaceDayPhase.RUNNING,2)
    val open = RaceState(race.id,RacePhase.BETTING_OPEN,version=1)
    val entry = PlayerEntry(day.id,PlayerId(uuid(30)),Money(1000),Money(100))
    var account = AccountSnapshot.opening(entry)
    var counter = 100
    fun purchase(type: BetType, selection: CouponSelection, units: Long = 1, state: RaceState = open,
                 expected: QuoteTerms? = null): PurchasePlan =
        BetPlacementPolicy.purchase(day,running,race,state,entry,account,type,selection,units,
            CouponId(uuid(counter++)),Instant.EPOCH,expected).ok()
}
class CoreTest : FunSpec({
    test("atomic equality, canonical ordering and ordered distinctions") {
        assertEquals(AtomicBet.Quinella(horse(1),horse(2)),AtomicBet.Quinella(horse(2),horse(1)))
        assertEquals(AtomicBet.Quinella(horse(1),horse(2)).hashCode(),AtomicBet.Quinella(horse(2),horse(1)).hashCode())
        assertNotEquals(AtomicBet.Exacta(horse(1),horse(2)),AtomicBet.Exacta(horse(2),horse(1)))
        assertNotEquals(AtomicBet.Trifecta(horse(1),horse(2),horse(3)),AtomicBet.Trifecta(horse(2),horse(1),horse(3)))
        rejected(ErrorCode.DUPLICATE_HORSE,AtomicBet.create(BetType.EXACTA,listOf(horse(1),horse(1))))
        rejected(ErrorCode.INVALID_SELECTION,AtomicBet.create(BetType.WIN,listOf(horse(1),horse(2))))
    }
    test("winning resolver for all types") {
        val result = RaceResult(RaceId(uuid(10)),"r1",listOf(horse(1),horse(2),horse(3)),ResultStatus.VERIFIED)
        for(type in BetType.entries) assertEquals(AtomicBet.create(type,result.finishers.take(type.positions)).ok(),
            WinningSelectionResolver.resolve(result,type).ok())
        rejected(ErrorCode.RESULT_NOT_VERIFIED,WinningSelectionResolver.resolve(
            RaceResult(result.raceId,"r1",result.finishers,ResultStatus.PROPOSED),BetType.WIN))
        rejected(ErrorCode.UNSUPPORTED_RESULT,WinningSelectionResolver.resolve(
            RaceResult(result.raceId,"r1",result.finishers,ResultStatus.VERIFIED,ResultKind.EXCEPTIONAL),BetType.WIN))
        rejected(ErrorCode.INVALID_RESULT,WinningSelectionResolver.resolve(
            RaceResult(result.raceId,"r1",listOf(horse(1)),ResultStatus.VERIFIED),BetType.TRIFECTA))
    }
    test("every wall position and all-wall rejection") {
        val roster = (1..5).map(::horse).toSet()
        val cases = listOf(BetType.EXACTA to listOf(positional(1,0),positional(0,1)),
            BetType.QUINELLA to listOf(positional(1,0),positional(0,1)),
            BetType.TRIFECTA to listOf(positional(1,0,0),positional(0,1,0),positional(0,0,1),
                positional(1,2,0),positional(1,0,2),positional(0,1,2)))
        for((type,selections) in cases) for(s in selections) {
            val expansion = SelectionExpander.expand(type,s,roster).ok()
            val walls = s.positions.count { it == PositionChoice.Wall }
            assertEquals(if(type == BetType.TRIFECTA) if(walls == 2) 12 else 3 else 4,expansion.combinations)
            assertEquals(expansion.bets.size,expansion.bets.toSet().size)
            assertTrue(expansion.bets.all { it.horses.distinct() == it.horses })
            assertEquals(expansion.bets,SelectionExpander.expand(type,s,roster.reversed().toSet()).ok().bets)
        }
        assertEquals(SelectionExpander.expand(BetType.QUINELLA,positional(1,0),roster).ok().bets,
            SelectionExpander.expand(BetType.QUINELLA,positional(0,1),roster).ok().bets)
        for(type in BetType.entries) rejected(ErrorCode.INVALID_SELECTION,
            SelectionExpander.expand(type,CouponSelection.Positional(List(type.positions) { PositionChoice.Wall }),roster))
    }
    test("boxes of each type are canonical, distinct and formula priced") {
        val roster = (1..5).map(::horse).toSet()
        val counts = mapOf(BetType.WIN to 3,BetType.QUINELLA to 3,BetType.EXACTA to 6,BetType.TRIFECTA to 6)
        for(type in BetType.entries) {
            val a = SelectionExpander.expand(type,box(1,2,3),roster).ok()
            val b = SelectionExpander.expand(type,box(3,1,2),roster).ok()
            assertEquals(counts[type],a.combinations)
            assertEquals(a.bets,b.bets)
        }
        val f = Fixture()
        val coupon = f.purchase(BetType.EXACTA,box(1,2,3),2).coupon
        assertEquals(Money(120),coupon.totalStake)
        assertEquals(6,coupon.legs.size)
        assertTrue(coupon.legs.all { it.units == BetUnits(2) && it.stake == Money(20) })
        assertEquals(6,coupon.legs.map { it.id }.toSet().size)
        assertEquals(1,f.purchase(BetType.WIN,positional(1)).coupon.legs.size)
    }
    test("selection rejections and preallocation limits") {
        val roster = (1..5).map(::horse).toSet()
        rejected(ErrorCode.UNKNOWN_HORSE,SelectionExpander.expand(BetType.WIN,positional(6),roster))
        rejected(ErrorCode.SCRATCHED_HORSE,SelectionExpander.expand(BetType.WIN,positional(1),roster,roster-horse(1)))
        rejected(ErrorCode.DUPLICATE_HORSE,SelectionExpander.expand(BetType.EXACTA,positional(1,1),roster))
        rejected(ErrorCode.DUPLICATE_HORSE,SelectionExpander.expand(BetType.EXACTA,box(1,1),roster))
        rejected(ErrorCode.INVALID_SELECTION,SelectionExpander.expand(BetType.TRIFECTA,box(1,2),roster))
        rejected(ErrorCode.INVALID_SELECTION,SelectionExpander.expand(BetType.WIN,box(1),roster))
        rejected(ErrorCode.EMPTY_EXPANSION,SelectionExpander.expand(BetType.EXACTA,positional(1,0),setOf(horse(1))))
        rejected(ErrorCode.EXPANSION_LIMIT,SelectionExpander.expand(BetType.EXACTA,box(1,2,3),roster,maximumLegs=5))
        val large = (1..10000).map(::horse)
        rejected(ErrorCode.EXPANSION_LIMIT,SelectionExpander.expand(BetType.TRIFECTA,CouponSelection.Box(large),large.toSet()))
    }
    test("observable collections are defensively copied and unmodifiable") {
        val source = mutableListOf(horse(1),horse(2))
        val selection = CouponSelection.Box(source)
        source.clear()
        assertEquals(2,selection.horses.size)
        assertFailsWith<UnsupportedOperationException> { (selection.horses as MutableList).clear() }
        val f = Fixture()
        val coupon = f.purchase(BetType.EXACTA,selection).coupon
        assertFailsWith<UnsupportedOperationException> { (coupon.legs as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (coupon.legs[0].bet.horses as MutableList).clear() }
    }
    test("quote changes require confirmation and purchase checks state, funds and units") {
        val f = Fixture()
        val quote = CouponPricingCalculator.quote(f.race.id,BetType.EXACTA,positional(1,0),
            f.race.roster,f.race.roster,1,Money(10)).ok()
        rejected(ErrorCode.QUOTE_CHANGED,BetPlacementPolicy.purchase(f.day,f.running,f.race,
            RaceState(f.race.id,RacePhase.BETTING_OPEN,setOf(horse(5))),f.entry,f.account,BetType.EXACTA,
            positional(1,0),1,CouponId(uuid(88)),Instant.EPOCH,quote.terms))
        fun attempt(day: RaceDayState = f.running, state: RaceState = f.open, units: Long = 1,
                    entry: PlayerEntry? = f.entry) = BetPlacementPolicy.purchase(f.day,day,f.race,state,entry,
            f.account,BetType.WIN,positional(1),units,CouponId(uuid(89)),Instant.EPOCH)
        rejected(ErrorCode.INVALID_UNITS,attempt(units=0))
        rejected(ErrorCode.INSUFFICIENT_BALANCE,attempt(units=1000))
        rejected(ErrorCode.PLAYER_NOT_ENROLLED,attempt(entry=null))
        rejected(ErrorCode.DAY_NOT_RUNNING,attempt(day=RaceDayState()))
        rejected(ErrorCode.BETTING_CLOSED,attempt(state=RaceState(f.race.id,RacePhase.BETTING_CLOSED)))
        rejected(ErrorCode.ARITHMETIC_OVERFLOW,attempt(units=Long.MAX_VALUE))
    }
    test("mandatory pool example and all take/empty/no-winner boundaries") {
        val key = PoolKey(RaceId(uuid(10)),BetType.WIN)
        val s = PoolSettlementCalculator.calculate(key,Money(1001),3,TakeRate(1500)).ok()
        assertEquals(Money(850),s.distributable); assertEquals(Money(283),s.dividendPerUnit)
        assertEquals(Money(849),s.distributed); assertEquals(Money(152),s.houseAllocation)
        for(take in listOf(0,1500,10000)) for(stake in listOf(0L,1,1001,Long.MAX_VALUE)) {
            for(winners in if(stake == 0L) listOf(0L) else listOf(0L,1,3,2000)) {
                val x = PoolSettlementCalculator.calculate(key,Money(stake),winners,TakeRate(take)).ok()
                assertEquals(stake,x.distributed.amount+x.houseAllocation.amount)
                if(take == 10000 || winners == 0L) assertEquals(Money.ZERO,x.distributed)
                if(take == 0 && winners == 1L) assertEquals(Money(stake),x.dividendPerUnit)
            }
        }
    }
    test("fixed-seed expansion pricing and pool conservation properties") {
        val random = Random(7931)
        repeat(250) {
            val n = random.nextInt(3,18)
            val roster = (1..n).map(::horse).toSet()
            val type = BetType.entries.random(random)
            val expected = when(type) {
                BetType.WIN -> n
                BetType.QUINELLA -> n*(n-1)/2
                BetType.EXACTA -> n*(n-1)
                BetType.TRIFECTA -> n*(n-1)*(n-2)
            }
            val selection = CouponSelection.Box(roster.shuffled(random))
            val quote = CouponPricingCalculator.quote(RaceId(uuid(10)),type,selection,roster,roster,
                random.nextLong(1,100),Money(random.nextLong(1,100))).ok()
            assertEquals(expected,quote.combinations)
            assertEquals(expected,quote.expansion.bets.toSet().size)
            assertTrue(quote.expansion.bets.all { b -> b.horses.distinct().size == type.positions })
            assertEquals(quote.totalStake.amount,quote.expansion.bets.sumOf { quote.unitStake.amount*quote.unitsPerCombination.value })
            val pool = PoolSettlementCalculator.calculate(PoolKey(quote.raceId,type),quote.totalStake,
                random.nextLong(0,quote.totalUnits+1),TakeRate(random.nextInt(10001))).ok()
            assertEquals(quote.totalStake,pool.distributed+pool.houseAllocation)
        }
    }
})
