# tiket-core

A standalone deterministic Kotlin/JVM domain library for a preplanned parimutuel horse-betting race day.
Targets Kotlin 2.4.x and Java 25. Runtime dependencies are limited to Kotlin and the JDK.

Implements Win, Quinella, Exacta and Trifecta; structured normal, wall and box selections;
immutable priced coupons; purchase plans; pool settlement; leg refunds; lifecycle policies;
single-balance accounts and promotional withdrawal eligibility.

No backend framework, persistence, network, clock, printing, frontend or simulator is included.

## Build

Run Gradle with a Java 25 JVM (set JAVA_HOME appropriately):

```text
./gradlew :tiket-core:clean :tiket-core:test :tiket-core:build
```

On Windows use gradlew.bat. Tests use Kotest on JUnit Platform, including reproducible generated
cases and a complete two-race in-memory scenario. Build also compiles an independent consumer source
set against the core JAR without internal/friend access.

## Consumer entry points

* Construct RaceDayDefinition (including displayNumber and timeZone), RaceDefinition (including racecourseName),
  RaceDayState and RaceState snapshots.
* Reload persisted records using CouponLeg.reconstitute, Coupon.reconstitute and AccountSnapshot.reconstitute;
  see the domain documentation for historical eligibility, movement replay and version requirements.
* Create PlayerEntry and AccountSnapshot.opening(entry) before starting the day.
* Use RaceDayLifecyclePolicy for event transitions and opening betting.
* Use CouponPricingCalculator.quote, then BetPlacementPolicy.purchase with optional quote.terms.
* Apply the returned debit with AccountProjector.apply in the same transaction that persists every coupon leg.
* Use RefundPlanner for scratches and voids; persist its resulting state and resolutions atomically with credits.
* Use PoolSnapshot.aggregate and RaceSettlementPlanner.plan for complete settlement.
* Apply planned credits and the resulting settled race state atomically.
* Use WithdrawalPolicy.quote after completion and AccountProjector.cashOut for final withdrawal/forfeiture.

Expected failures return Decision.Rejected(DomainError). Construction of invalid value objects
throws DomainException; overflowing direct value-object arithmetic throws ArithmeticException.
Operation boundaries convert both into structured rejections.

See [domain model](docs/domain-model.md), [betting rules](docs/betting-rules.md),
and [financial rules](docs/financial-rules.md). The integration test is an executable consumer example.
