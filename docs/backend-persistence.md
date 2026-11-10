# Backend persistence

Phase 2 uses explicit Spring JDBC and PostgreSQL. V1 is preserved; V2 adds the domain schema.

Relationships: race_day -> race -> race_runner, race_state, race_result -> race_result_finisher;
race -> pool_rules -> pool_state -> pool_selection_units;
race_day -> player_entry -> account_balance, account_movement;
coupon -> coupon_leg -> leg_resolution; pool_settlement -> payout.
Composite foreign keys bind race-day, race, player, pool and coupon contexts.

Definitions use public core constructors. CouponLeg.reconstitute and Coupon.reconstitute validate
frozen purchased expansions. Original selections are normalized as a variant plus ordered choice
rows (HORSE/WALL or BOX), with purchase eligibility stored separately. No JVM serialization.
Atomic selections use ordered UUID columns and core AtomicBet.create; Quinella is canonicalized.
Runner ordinals preserve display order; leg ordinals are zero-based core identities.

AccountSnapshot.reconstitute is the only public historical account factory. It requires full
ordered ledger replay and validates the stored AccountProjection. There is no safe public factory
for a policy-ready snapshot from projection totals alone. Efficient totals reads return the
AccountProjection value, never an AccountSnapshot. Ordinary domain policy calls currently replay
history; a separately reviewed core API will be needed for bounded-cost historical snapshots.
Opening funding starts in immutable player_entry; V3 adds append-only pre-start adjustments. No extra wallets.

RaceResult constructors do not validate finishers; adapters validate roster membership, uniqueness,
revision and verified arity. Exceptional results are stored but cannot be used by normal core
settlement policy. PoolSnapshot needs adapter checks for unit-stake equality. PoolSettlement and
LegResolution public records likewise require adapter validation.

All compound writes run in Spring transactions. Application services must wrap complete plans in
PersistenceTransactions.required: purchase writes header, legs, debit, pool projections and
idempotency; refunds write terminal resolutions, credits, pool reductions and state; settlement
writes four pools, outcomes, credits and state; cashout writes both terminal movements and projection.
Phase 3 application services compose the administrative operations described below.

Lock order: day state when event-wide coordination is needed, then race state, accounts sorted
by UUID text, pools sorted by bet-type name, then subordinate records. Lock helpers require an
active transaction. Version updates compare expected version and advance by one per domain mutation (account batches advance by the number of core steps, with cashout counting as one); zero affected
rows throw StaleVersionException. Acquire locks before loading mutable snapshots. Do not combine
unlocked reads across multiple SQL statements when a consistent aggregate is needed.

The ledger is authoritative. Account writes verify the complete resulting snapshot against replay,
append only the new suffix, and update projection with a version check in the same transaction.
Core enforces movement semantics. PostgreSQL rejects duplicate movement keys, coupon stakes,
refunds, winnings, payouts, settlements and terminal resolutions. Historical rows reject UPDATE
and DELETE through triggers; schema owners can disable triggers, so production runtime roles
must not own tables or have DDL rights. Migration credentials should be separate.

Idempotency executes inside the business transaction: INSERT ON CONFLICT DO NOTHING waits for
a competing transaction. A committed matching fingerprint replays the stored stable reference;
a different fingerprint fails. A failed transaction removes its reservation and all effects.
Pending reservations are never committed: a deferred constraint trigger checks completion.
The callback must return a durable result reference. External effects have no exactly-once guarantee.
Scope, operation and key are caller-supplied stable identifiers; fingerprints must cover canonical
validated request fields, including POS/cashier metadata where relevant.

Database checks enforce basic ranges, uniqueness, roster ownership and parent context. Core and
future application services enforce lifecycle authorization, selection expansion, complete four-pool
settlement, legitimate financial plans and final cashout eligibility. Repository methods are storage
primitives, not permission checks. Setup insertion does not authorize late enrollment.

Indexes cover parent and ordered history lookups. Aggregate loads use multiple queries and should
run under locks or repeatable-read for consistency. Ledger replay is currently unbounded. Large
race coupon scans and full pool replacement may need batching in later measured optimizations.
Never remove historical purchased legs to optimize aggregates.

Run tests with Docker and Java 25:
gradlew.bat clean build --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
gradlew.bat :tiket-backend:test --no-configuration-cache
gradlew.bat :tiket-backend:bootJar --no-configuration-cache
Testcontainers uses ephemeral ports and isolated databases. Flyway validates checksums on startup;
never edit applied migrations. New changes require a new versioned migration.

## Mapping reference

| Domain value | Durable representation and reconstruction |
| --- | --- |
| RaceDayDefinition, RaceDayRules | race_day plus ordered races and all four pool_rules per race; public constructors validate the complete definition |
| RaceDayState | race_day_state phase and version |
| RaceDefinition, RaceRunner | race and race_runner ordered by ordinal; constructor validates roster identities and program numbers |
| RaceState | race_state plus race_scratch and selected result revision |
| RaceResult | race_result plus finishers ordered by position; proposal sequence preserves revision insertion order |
| PoolRules | pool_rules unit stake and take basis points through Money and TakeRate |
| PoolSnapshot | pool_state plus canonical pool_selection_units; adapter verifies stake and total units before construction |
| PlayerEntry | player_entry opening paid/freebet through Money |
| AccountProjection | account_balance totals, version and finalization flag; display-only projection |
| AccountSnapshot | full AccountSnapshot.reconstitute replay, compared to AccountProjection and cashout flag |
| AccountMovement | account_movement stable scoped key; identity sequence gives deterministic order; timestamp is backend metadata |
| CouponSelection | coupon.selection_kind plus coupon_selection zero-based positions; HORSE/WALL preserve positional meaning, BOX preserves explicit horses |
| Coupon, CouponLeg | coupon header, coupon_leg and coupon_eligibility; public reconstitution validates historical expansion without current roster eligibility |
| LegResolution | one terminal leg_resolution per BetLegId; active is represented by absence |
| PoolSettlement | settlement groups four pool_settlement rows with a verified result revision and per-pool version |
| Payout | payout ties a winning resolution to its exact account movement, player and amount |
| PurchasePlan, RefundPlan, RaceSettlementPlan | transient decisions, not serialized; persist their coupons, movements, projections, resolutions, settlements and resulting states atomically |

Deferred database checks reject incomplete coupon headers and settlements at commit. A result revision
can only change by verifying an otherwise identical proposal. Payout inserts must reference the exact
matching winnings movement and won resolution. Normal financial formulas remain in core.

## Validation

The implementation was exercised against Docker PostgreSQL 18 using Spring Boot application contexts.
The suite includes fresh migration, V1 upgrade, repeated validation, missing migration detection,
four coupon variants, scratch stability, intentionally corrupted data, ledger reconciliation and
cashout, four pool settlements, payout deduplication, terminal resolutions, cross-day reads, version
conflicts, independent-transaction contention and complete rollback. Transport tests explicitly use
a database-free configuration; the PostgreSQL foundation test still exercises the full application.
Phase 3 can compose these repositories inside PersistenceTransactions.required after acquiring
PersistenceLocks in the documented order, call core policies, and store their plans. Phase 3 implements enrollment and administration; Phase 4 implements purchases;
Phase 5 implements refunds, results and settlement.

## V3 administration support

V3 preserves V1/V2 and immutable player_entry. Effective opening funds are original amounts plus
append-only opening_funding_adjustment rows. account_balance.opening_version advances with the account
version before activity; AccountSnapshot.reconstitute receives this version, including after restart.
Account append validation also retains openingVersion. No funding movements or extra wallets are created.
Triggers reject late/activity funding and reconcile adjustment sequences/projections at commit.
Race ordinal preserves planned order; existing V2 races backfill by number. New definitions store list indices.
The Phase 3 application services now compose the persistence primitives; see [administration](phase3-administration.md).

## V4 betting and printing

Purchases now compose coupon, batched legs, original eligibility, account movement/projection,
incremental target pool UPSERTs, original print job and exact command acknowledgement in one transaction.
PoolRepository.acceptPurchase takes only a core PurchasePlan and reconciles checked stake/unit totals.
V4 adds print_job and print_attempt, a unique original-job index, pending/history indexes and a
player coupon index. Claim leases use SKIP LOCKED; owner checks prevent stale completion.
Printing dispatch occurs outside database transactions, with bounded retry and at-least-once recovery.
See [Phase 4](phase4-betting-printing.md) for schema fields, state transitions and security limits.

## Phase 5 financial orchestration

V1–V4 are unchanged and no V5 is required. Refunds batch terminal resolutions and use checked
PoolRepository.applyRefundDelta to update only affected pool/selection rows and remove zero counts.
The complete remaining active-leg aggregates are reconciled before and after reductions.
Verification updates race_state before ResultRepository.verify inside one transaction; final reconstruction
checks both statuses. Settlement inserts its header/four pools, outcomes, grouped account movements,
payouts and final race state in foreign-key-compatible order. AccountSnapshot still replays full history.
No historical coupon or print row changes. See [Phase 5](phase5-refunds-settlement.md) for sequencing,
conservation, deterministic locks, replay, corruption detection, concurrency and rollback tests.
