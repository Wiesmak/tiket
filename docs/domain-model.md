# Domain model and application contract

The packages are identity, money, race, raceday, betting, coupon, pool, settlement, accounting and validation.
UUID-backed IDs come from callers. BetLegId is coupon ID plus zero-based canonical ordinal.
Program numbers are presentation data only. Currency is an explicit event-wide identifier.
RaceDayDefinition additionally requires displayNumber (1–999) and timeZone (java.time.ZoneId).
The display number is independent of RaceDayId; adapters may format it as 001–999.
RaceDefinition additionally requires a nonblank racecourseName; number and title remain separate fields.
These metadata arguments have no implicit defaults, so consumers must supply event-specific values.
No time zone is read from the host environment.

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
and at least three distinct valid ordered finishers. An incomplete nonempty proposal may be corrected
before verification. Unsupported exceptional results retain their explicit rejection. Settlement is produced only by RaceSettlementPlanner.
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

## Reconstitution after persistence

Public factories return Decision and leave constructors internal:

* CouponLeg.reconstitute(id, bet, units, unitStake, stake) validates its positive unit stake
  and checked stake multiplication. BetUnits and Money retain their smart-constructor invariants.
* Coupon.reconstitute(...) restores the entire original purchase, not merely its remaining active legs.
  It validates coupon ownership, unique IDs, contiguous zero-based ordinals in canonical atomic order,
  bet type, uniform positive quantities, frozen stake arithmetic, unique canonical outcomes,
  expression compatibility and total stake. The original structured expression and timestamp are retained.
* AccountSnapshot.reconstitute(entry, movements, version, expectedProjection?, openingVersion=0)
  replays ordered financial facts from the frozen opening funding. AccountProjection.from(snapshot)
  supplies optional stored balance/accepted/refunded/winnings totals for comparison only.

A restored race definition may be a different JVM instance. Purchase compares ID, number, title,
racecourse name and the full ordered immutable runner records against the event's definition.
A matching ID with conflicting data is rejected. Race-state identity and scratched-roster checks still apply.

### Original coupons and historical eligibility

Persist every original leg, even if refunded, and persist LegResolution records separately.
The coupon factory never reads operational race state or substitutes current eligibility.

For normal and box selections, the original expression determines every leg, so completeness is checked.
For walls, without additional evidence the factory verifies completeness only over the horse IDs
evidenced by the original legs. It cannot detect omission of an entire wildcard horse and all its legs.
For full wall completeness verification, persist the eligible IDs at purchase time and pass them as
purchaseEligibleRunners. The set must be historical evidence, not a post-scratch eligible set.
Historical checks are bounded by the supplied leg count, not the current event's maximum-coupon rule.
The application remains responsible for loading records under the correct event/race/player and for
checking horse references against the immutable race roster.

```kotlin
val legs = persistedLegs.map {
    CouponLeg.reconstitute(it.id, it.bet, it.units, frozenUnitStake, it.stake).getOrThrow()
}
val coupon = Coupon.reconstitute(
    couponId, dayId, raceId, playerId, betType, originalSelection,
    quantity, frozenUnitStake, frozenTotalStake, legs, purchasedAt,
    purchaseEligibleRunners = eligibleIdsRecordedAtPurchase
).getOrThrow()
```

### Account replay and versions

Movement keys must be nonblank and globally unique within an account.
Replay uses AccountProjector checks for stake debits, refund ownership/limits and winning-leg credits;
optional coupon/leg references must agree. Negative balances, arithmetic overflow, duplicate operations
and mismatched stored projections are rejected. Opening amounts use validated Money values and their
sum is checked by PlayerEntry.

Version is checked exactly: openingVersion plus one per ordinary financial movement plus one for
the atomic cashout pair. openingVersion defaults to zero; if opening funding was edited before start,
persist that frozen opening record's version and supply it on reload. The factory does not guess
missing funding revisions from arbitrary balance totals.

A cashed-out history must end with FINAL_WITHDRAWAL followed by PROMOTIONAL_FORFEITURE.
Both records must be present, even for zero amounts, and equal the calculated withdrawal/forfeiture
at that point. No later activity is allowed. Restoring a cashed-out account preserves the full history
and zero balance, and replay/cashout protections remain effective.

```kotlin
val account = AccountSnapshot.reconstitute(
    frozenEntry, orderedMovements, storedVersion,
    expectedProjection = AccountProjection(storedBalance, accepted, refunded, winnings),
    openingVersion = frozenOpeningVersion
).getOrThrow()
```

Replay verifies financial consistency, not the authenticity of a winning result or event completion.
Persisted movements must remain authoritative outputs of validated plans and cashout policy.
This is record reconstitution, with no database, serializer, or event-sourcing infrastructure.

The build's consumerCompatibility source set compiles as a distinct Kotlin compiler module against
the core JAR, without friend paths. It exercises all restoration factories and runs during check/build;
it adds no Gradle subproject.
