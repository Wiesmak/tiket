package pl.umamusume.tiket.core

import io.kotest.core.spec.style.FunSpec
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.coupon.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.*
import pl.umamusume.tiket.core.pool.*
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.raceday.*
import pl.umamusume.tiket.core.settlement.*
import pl.umamusume.tiket.core.validation.*

private fun restoreCoupon(c: Coupon, legs: List<CouponLeg> = c.legs,
                          total: Money = c.totalStake, unitStake: Money = c.unitStake,
                          selection: CouponSelection = c.selection,
                          eligibleAtPurchase: Set<HorseId>? = null): Decision<Coupon> =
    Coupon.reconstitute(c.id, c.raceDayId, c.raceId, c.playerId, c.betType, selection,
        c.unitsPerCombination, unitStake, total, legs, c.purchasedAt, eligibleAtPurchase)

class CompatibilityTest : FunSpec({
    test("purchase accepts a separately loaded identical race and original instance") {
        val f = Fixture()
        val loaded = RaceDefinition(f.race.id, f.race.number, f.race.title,
            f.race.runners.map { it.copy() }, f.race.racecourseName)
        assertNotSame(f.race, loaded)
        for (race in listOf(f.race, loaded)) {
            val plan = BetPlacementPolicy.purchase(f.day, f.running, race, f.open, f.entry, f.account,
                BetType.WIN, positional(1), 2, CouponId(uuid(400)), Instant.EPOCH).ok()
            assertEquals(Money(20), plan.coupon.totalStake)
        }
    }
    test("purchase rejects conflicting definition fields, foreign race, state and scratches") {
        val f = Fixture()
        val conflicts = listOf(
            RaceDefinition(f.race.id, 9, f.race.title, f.race.runners, f.race.racecourseName),
            RaceDefinition(f.race.id, 1, "Changed", f.race.runners, f.race.racecourseName),
            RaceDefinition(f.race.id, 1, f.race.title, f.race.runners, "Changed course"),
            RaceDefinition(f.race.id, 1, f.race.title, f.race.runners.reversed(), f.race.racecourseName),
            RaceDefinition(f.race.id, 1, f.race.title,
                f.race.runners.map { it.copy(displayName="Changed") }, f.race.racecourseName),
            RaceDefinition(RaceId(uuid(999)), 1, f.race.title, f.race.runners, f.race.racecourseName))
        fun attempt(race: RaceDefinition, state: RaceState = f.open) =
            BetPlacementPolicy.purchase(f.day, f.running, race, state, f.entry, f.account,
                BetType.WIN, positional(1), 1, CouponId(uuid(400)), Instant.EPOCH)
        for (race in conflicts) rejected(ErrorCode.INVALID_DEFINITION, attempt(race))
        rejected(ErrorCode.INVALID_DEFINITION, attempt(f.race, RaceState(f.second.id, RacePhase.BETTING_OPEN)))
        rejected(ErrorCode.INVALID_DEFINITION, attempt(f.race,
            RaceState(f.race.id, RacePhase.BETTING_OPEN, setOf(horse(999)))))
    }
    test("immutable printable metadata is explicit and range validated") {
        val f = Fixture()
        fun day(number: Int, zone: ZoneId = f.day.timeZone) = RaceDayDefinition(f.day.id, f.day.date,
            f.day.currency, f.day.races, f.day.rulesVersion, f.day.pools, f.day.rules, number, zone)
        for (number in listOf(1, 999)) {
            val restored = day(number, ZoneId.of("Asia/Tokyo"))
            assertEquals(number, restored.displayNumber)
            assertEquals(f.day.id, restored.id)
            assertEquals(ZoneId.of("Asia/Tokyo"), restored.timeZone)
        }
        for (number in listOf(0, 1000)) assertFailsWith<DomainException> { day(number) }
        assertFailsWith<DomainException> {
            RaceDefinition(f.race.id, 1, f.race.title, f.race.runners, "  ")
        }
        assertEquals("Tokyo", f.race.racecourseName)
        assertEquals(1, f.race.number); assertEquals("First", f.race.title)
    }
    test("incomplete proposal can be corrected but cannot be verified") {
        val f = Fixture()
        fun result(ids: List<Int>, status: ResultStatus, revision: String = "r1") =
            RaceResult(f.race.id, revision, ids.map(::horse), status)
        var state = RaceLifecyclePolicy.transition(f.race,
            RaceState(f.race.id, RacePhase.BETTING_CLOSED), RacePhase.RESULT_PROPOSED,
            result(listOf(1), ResultStatus.PROPOSED)).ok()
        rejected(ErrorCode.INVALID_RESULT, RaceLifecyclePolicy.transition(f.race, state,
            RacePhase.RESULT_VERIFIED, result(listOf(1), ResultStatus.VERIFIED)))
        rejected(ErrorCode.INVALID_RESULT, ResultValidator.validate(f.race, state,
            result(listOf(1,2), ResultStatus.VERIFIED)))
        state = RaceLifecyclePolicy.transition(f.race, state, RacePhase.RESULT_PROPOSED,
            result(listOf(1,2,3), ResultStatus.PROPOSED, "r2")).ok()
        val verified = RaceLifecyclePolicy.transition(f.race, state, RacePhase.RESULT_VERIFIED,
            result(listOf(1,2,3), ResultStatus.VERIFIED, "r2")).ok()
        assertEquals(RacePhase.RESULT_VERIFIED, verified.phase)
        for (ids in listOf(emptyList(), listOf(1,1,2), listOf(1,2,99))) rejected(ErrorCode.INVALID_RESULT,
            ResultValidator.validate(f.race, state, result(ids, ResultStatus.VERIFIED)))
        rejected(ErrorCode.INVALID_RESULT, ResultValidator.validate(f.race,
            RaceState(f.race.id, RacePhase.RESULT_PROPOSED, setOf(horse(3))),
            result(listOf(1,2,3), ResultStatus.VERIFIED)))
        rejected(ErrorCode.UNSUPPORTED_RESULT, ResultValidator.validate(f.race, state,
            RaceResult(f.race.id, "exceptional", listOf(horse(1)), ResultStatus.VERIFIED, ResultKind.EXCEPTIONAL)))
    }
    test("original normal, wall and box coupons round-trip after partial refunds") {
        val f = Fixture()
        for ((type, selection) in listOf(BetType.WIN to positional(1),
            BetType.EXACTA to positional(1,0), BetType.QUINELLA to box(1,2,3))) {
            val original = f.purchase(type, selection, 2).coupon
            val refund = RefundPlanner.plan(f.race, f.open, RefundReason.Scratch(horse(3)), listOf(original)).ok()
            val persisted = original.legs.map { leg ->
                CouponLeg.reconstitute(leg.id, AtomicBet.create(type, leg.bet.horses.toList()).ok(),
                    BetUnits(leg.units.value), Money(original.unitStake.amount), Money(leg.stake.amount)).ok()
            }.toMutableList()
            val restored = restoreCoupon(original, persisted).ok()
            persisted.clear()
            assertNotSame(original, restored)
            assertEquals(original.legs, restored.legs)
            assertEquals(original.selection, restored.selection)
            assertEquals(original.purchasedAt, restored.purchasedAt)
            assertEquals(original.playerId, restored.playerId)
            assertEquals(original.raceId, restored.raceId)
            assertEquals(original.raceDayId, restored.raceDayId)
            assertFailsWith<UnsupportedOperationException> { (restored.legs as MutableList).clear() }
            val resolutions = refund.resolutions.associateBy { it.legId }
            val key = PoolKey(f.race.id, type)
            val before = PoolSnapshot.aggregate(key, listOf(original), resolutions).ok()
            val after = PoolSnapshot.aggregate(key, listOf(restored), resolutions).ok()
            assertEquals(before.units, after.units); assertEquals(before.validStake, after.validStake)
            assertTrue(RefundPlanner.plan(f.race, refund.resultingState, RefundReason.Scratch(horse(3)),
                listOf(restored), resolutions).ok().credits.isEmpty())
            assertEquals(original.legs, restoreCoupon(original, eligibleAtPurchase=f.race.roster).ok().legs)
        }
    }
    test("coupon restoration rejects corrupt ownership, ordinal, atomic, units and financial records") {
        val c = Fixture().purchase(BetType.EXACTA, box(1,2,3), 2).coupon
        fun invalid(legs: List<CouponLeg> = c.legs, total: Money = c.totalStake,
                    stake: Money = c.unitStake, selection: CouponSelection = c.selection) {
            assertIs<Decision.Rejected>(restoreCoupon(c, legs, total, stake, selection))
        }
        invalid(emptyList()); invalid(total=Money(1)); invalid(stake=Money.ZERO)
        invalid(c.legs + c.legs.first())
        invalid(c.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(id=BetLegId(CouponId(uuid(999)),0)) else leg })
        invalid(c.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(id=BetLegId(c.id,9)) else leg })
        invalid(c.legs.reversed())
        invalid(c.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(bet=AtomicBet.Win(horse(1))) else leg })
        invalid(c.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(units=BetUnits(1)) else leg })
        invalid(c.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(stake=Money(1)) else leg })
        invalid(c.legs.mapIndexed { i, leg -> if (i == 1) leg.copy(bet=c.legs.first().bet) else leg })
        invalid(selection=box(1,2,4))
        invalid(selection=box(1,1,2))
        val missing = c.legs.dropLast(1)
        invalid(missing, Money(missing.sumOf { it.stake.amount }))
        rejected(ErrorCode.INCONSISTENT_POOL, CouponLeg.reconstitute(c.legs[0].id, c.legs[0].bet,
            BetUnits(2), Money(10), Money(1)))
        rejected(ErrorCode.INVALID_STAKE, CouponLeg.reconstitute(c.legs[0].id, c.legs[0].bet,
            BetUnits(2), Money.ZERO, Money.ZERO))
        rejected(ErrorCode.ARITHMETIC_OVERFLOW, CouponLeg.reconstitute(c.legs[0].id, c.legs[0].bet,
            BetUnits(Long.MAX_VALUE), Money(2), Money(1)))
    }
    test("historical wall completeness uses purchase evidence and never current eligibility") {
        val f = Fixture()
        val c = f.purchase(BetType.EXACTA, positional(1,0)).coupon
        val incomplete = c.legs.dropLast(1)
        val total = Money(incomplete.sumOf { it.stake.amount })
        // Missing an entire wildcard horse cannot be inferred from original legs alone.
        assertIs<Decision.Accepted<Coupon>>(restoreCoupon(c, incomplete, total))
        assertIs<Decision.Rejected>(restoreCoupon(c, incomplete, total, eligibleAtPurchase=f.race.roster))
        assertIs<Decision.Rejected>(restoreCoupon(c, eligibleAtPurchase=f.race.roster-horse(5)))
        assertEquals(c.legs, restoreCoupon(c).ok().legs)
    }
    fun activeAccount(): AccountSnapshot {
        val f = Fixture()
        val coupon = f.purchase(BetType.EXACTA, positional(1,0)).coupon
        var account = AccountProjector.apply(f.account,
            AccountMovement("stake", MovementType.COUPON_STAKE, coupon.totalStake, coupon.id)).ok()
        val refund = RefundPlanner.plan(f.race, f.open, RefundReason.Scratch(horse(3)), listOf(coupon)).ok()
        account = AccountProjector.apply(account, refund.credits.single().movement).ok()
        val winningLeg = coupon.legs.first { horse(3) !in it.bet.horses }
        return AccountProjector.apply(account, AccountMovement("win", MovementType.WINNINGS,
            Money(150), coupon.id, winningLeg.id, SettlementId(uuid(600)))).ok()
    }
    test("accounts round-trip from authoritative movements with optional projection") {
        val original = activeAccount()
        val source = original.movements.map { it.copy() }.toMutableList()
        val restored = AccountSnapshot.reconstitute(original.entry.copy(), source, original.version,
            AccountProjection.from(original)).ok()
        source.clear()
        assertEquals(original.movements, restored.movements)
        assertEquals(original.qualifyingTurnover, restored.qualifyingTurnover)
        assertEquals(WithdrawalPolicy.hypothetical(original), WithdrawalPolicy.hypothetical(restored))
        assertFailsWith<UnsupportedOperationException> { (restored.movements as MutableList).clear() }
        val closed = AccountProjector.cashOut(original, RaceDayState(RaceDayPhase.COMPLETED)).ok()
        val loadedClosed = AccountSnapshot.reconstitute(closed.entry, closed.movements, closed.version,
            AccountProjection.from(closed)).ok()
        assertEquals(Money.ZERO, loadedClosed.balance)
        assertEquals(closed.movements, loadedClosed.movements)
        rejected(ErrorCode.DUPLICATE_OPERATION, AccountProjector.cashOut(loadedClosed, RaceDayState(RaceDayPhase.COMPLETED)))
        assertIs<Decision.Rejected>(AccountProjector.apply(loadedClosed, original.movements.first().copy(key="later")))
    }
    test("restoration supports opening funding versions and zero withdrawal or forfeiture") {
        val f = Fixture()
        val funded = EnrollmentPolicy.addOpeningFunding(RaceDayState(), f.account, Money(5)).ok()
        val restored = AccountSnapshot.reconstitute(funded.entry, funded.movements, funded.version,
            openingVersion=funded.version).ok()
        assertEquals(funded.balance, restored.balance)
        for ((paid, free) in listOf(0L to 100L, 100L to 0L, 0L to 0L)) {
            val closed = AccountProjector.cashOut(AccountSnapshot.opening(
                PlayerEntry(f.day.id, f.entry.playerId, Money(paid), Money(free))),
                RaceDayState(RaceDayPhase.COMPLETED)).ok()
            assertEquals(closed.movements, AccountSnapshot.reconstitute(closed.entry,
                closed.movements, closed.version).ok().movements)
        }
    }
    test("account restoration rejects corrupt history, projections, versions and partial cashout") {
        val a = activeAccount()
        fun invalid(history: List<AccountMovement> = a.movements, version: Long = a.version,
                    projection: AccountProjection? = null, openingVersion: Long = 0) {
            assertIs<Decision.Rejected>(AccountSnapshot.reconstitute(a.entry, history, version, projection, openingVersion))
        }
        invalid(a.movements + a.movements.first())
        invalid(a.movements.mapIndexed { i,m -> if(i == 0) m.copy(key=" ") else m })
        invalid(a.movements.reversed())
        invalid(version=-1); invalid(version=a.version+1); invalid(openingVersion=-1)
        val p = AccountProjection.from(a)
        invalid(projection=p.copy(balance=Money(1)))
        invalid(projection=p.copy(acceptedStakes=Money.ZERO))
        invalid(projection=p.copy(refundedStakes=Money.ZERO))
        invalid(projection=p.copy(winnings=Money.ZERO))
        invalid(a.movements.mapIndexed { i,m -> if(i == 1) m.copy(amount=Money(999)) else m })
        invalid(a.movements.mapIndexed { i,m -> if(i == 1) m.copy(couponId=CouponId(uuid(999))) else m })
        val closed = AccountProjector.cashOut(a, RaceDayState(RaceDayPhase.COMPLETED)).ok()
        invalid(closed.movements.dropLast(1), closed.version)
        invalid(closed.movements + a.movements.first().copy(key="post-close"), closed.version+1)
        invalid(closed.movements.map { if(it.type == MovementType.FINAL_WITHDRAWAL) it.copy(amount=Money(1)) else it }, closed.version)
        invalid(closed.movements.map { if(it.type == MovementType.PROMOTIONAL_FORFEITURE) it.copy(amount=Money(1)) else it }, closed.version)
        val f = Fixture()
        val enormous = PlayerEntry(f.day.id, f.entry.playerId, Money(Long.MAX_VALUE), Money.ZERO)
        rejected(ErrorCode.ARITHMETIC_OVERFLOW, AccountSnapshot.reconstitute(enormous,
            listOf(AccountMovement("s", MovementType.COUPON_STAKE, Money(1), CouponId(uuid(1))),
                AccountMovement("w", MovementType.WINNINGS, Money(2), CouponId(uuid(1)),
                    BetLegId(CouponId(uuid(1)), 0), SettlementId(uuid(2)))), 2))
    }
})
