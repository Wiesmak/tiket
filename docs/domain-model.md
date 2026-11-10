# Domain model and application contract

The packages are identity, money, race, raceday, betting, coupon, pool, settlement, accounting and validation.
UUID-backed IDs come from callers. BetLegId is coupon ID plus zero-based canonical ordinal.
Program numbers are presentation data only. Currency is an explicit event-wide identifier.

AtomicBet describes one outcome, with no ownership or stake. CouponSelection describes the requested
positional or box expression. CouponLeg records a purchased atomic bet with frozen units and stake.
Coupon records one player/race/type purchase. Refunds and settlement never change its original fields.

Collections are copied into unmodifiable collections at construction. Atomic bets and selection values
have structural equality; snapshot records with collection fields deliberately do not expose data-class copy methods.
No operation reads the system clock, generates IDs, accesses infrastructure or mutates an input.

## Lifecycle

Event progression: DRAFT → READY → RUNNING → FINALIZING → COMPLETED → CLOSED.
Definitions freeze at READY; enrollment/opening funding freeze at RUNNING.
Finalization requires betting closed; completion requires settled/voided races and no outstanding legs;
closing requires completed cashout obligations. The caller supplies complete reconciliation evidence.

Race progression: SCHEDULED → BETTING_OPEN → BETTING_CLOSED → RESULT_PROPOSED →
RESULT_VERIFIED → SETTLED. Proposals may be corrected. Verification confirms the proposed revision
and ordered finishers. Settlement is produced only by RaceSettlementPlanner.
Voiding is permitted before verification; later correction requires exceptional administration.
A scratch invalidates a proposed result. Scratches never remove roster records.

Use RaceDayLifecyclePolicy.openBetting with all race states to enforce maximumOpenRaces (default 1).
RaceLifecyclePolicy supplies individual race transition checks; event constraints must also be enforced.
Enrollment/deposit/definition policy predicates are intended for the future application's command handlers.

## Atomic application

Plans are instructions, not reservations. Persist all legs and account debit together.
A confirmed quote uses full comparable terms rather than a hash; purchase recomputes eligibility and price.
Purchase plans carry event/race/account versions. Refund and settlement plans carry the race version;
settlement also carries pool versions, result revision and caller-supplied settlement ID.

The backend must compare versions, enforce unique coupon/leg/settlement/movement identities, and atomically
apply a plan once. Every refund must advance the race version with its resolutions and pool adjustments.
AccountProjector rejects duplicate financial facts, insufficient balances and over-refunds.
The application must accept only validated plan movements: a raw movement alone cannot authenticate
the stake or result of a leg. Cashout uses a single projection operation for withdrawal and forfeiture.

Pool projections are checked against active historical coupon legs before settlement. The caller must
supply the complete authoritative coupon/resolution set; absence cannot be inferred by a pure library.
Refund plans are idempotent when existing resolutions are supplied: repeated requests produce no credits.

## Minimal structured selection example

```kotlin
val selection = CouponSelection.Positional(
    listOf(PositionChoice.Horse(horseA), PositionChoice.Wall)
)
val quote = CouponPricingCalculator.quote(
    race.id, BetType.EXACTA, selection, race.roster,
    raceState.eligible(race), 2, poolRules.unitStake
)
```

Unwrap Decision.Accepted or translate DomainError into your transport's rejection.
Pass the accepted quote's terms into BetPlacementPolicy.purchase; its other arguments are authoritative
event, race, enrollment and account snapshots, caller IDs and purchase timestamp.

## Purchase and settlement example

The variables below are authoritative snapshots and caller-supplied IDs, rather than services:

```kotlin
val quoted = CouponPricingCalculator.quote(
    race.id, BetType.EXACTA, selection, race.roster,
    raceState.eligible(race), 2, poolRules.unitStake
).getOrThrow()

val purchase = BetPlacementPolicy.purchase(
    day, dayState, race, raceState, entry, account,
    BetType.EXACTA, selection, 2, couponId, purchaseTimestamp,
    expectedQuote = quoted.terms
).getOrThrow()
val debited = AccountProjector.apply(account, purchase.debit).getOrThrow()
// Persist coupon, all legs, pool increases and debited projection atomically.

val snapshots = BetType.entries.associate { type ->
    val key = PoolKey(race.id, type)
    key to PoolSnapshot.aggregate(key, allRaceCoupons, resolutions).getOrThrow()
}
val rules = BetType.entries.associateWith { day.pools.getValue(PoolKey(race.id, it)) }
val settlement = RaceSettlementPlanner.plan(
    settlementId, race, verifiedRaceState, rules,
    snapshots, allRaceCoupons, resolutions
).getOrThrow()
// Compare expected versions, then persist all resolutions, credits and resultingState together.
```

EnrollmentPolicy.enroll constructs an opening account while enrollment is allowed.
EnrollmentPolicy.addOpeningFunding replaces its opening record and advances the account version
only before the event starts and before account activity. This is the only funding operation;
it does not introduce an in-event deposit category.
