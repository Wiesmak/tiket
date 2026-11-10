# Phase 5: refunds, results and settlement

## Contracts and ownership

The version-1 Java gRPC contracts preserve every existing operation. RaceService adds
ScratchHorse(ScratchHorseRequest) and VoidRace(RaceCommand). ResultService adds SubmitResult,
VerifyResult, GetResult, ListResultRevisions, SettleRace, GetSettlement and ListRaceLegResolutions.
RaceFinancialApplicationService orchestrates these operations; core owns every eligibility,
refund, lifecycle and settlement decision. No REST, new betting type or wallet is introduced.

Mutation requests carry a RaceCommand with race-day/race UUIDs, both expected versions and an
idempotency key. ScratchHorse adds a horse UUID; SubmitResult adds a unique revision (1–128
ASCII letters/digits, dot, underscore, colon or hyphen, starting with a letter/digit), typed kind
and ordered horse UUIDs. VerifyResult names the selected revision. SettleRace requires a
client-generated settlement UUID. Financial amounts and unit counts use int64.

RefundAcknowledgement contains resulting RaceAcknowledgement, refunded leg/stake totals and
per-player/per-pool summaries. RaceAcknowledgement also exposes sorted scratched horse UUIDs
and the selected result revision (empty when absent). ResultAcknowledgement includes current
race state and optional selected OfficialResult. After invalidation or void there is no selected result.
The historical revisions query still returns the old records.

SettlementInformation contains identity, race/day state, verified revision, four ordered pool
summaries (valid stake, winning units, distributable, dividend, distributed, house and frozen
pool version) and player winnings summaries. GetSettlement requires matching day, race and
settlement UUIDs. Settlement command replay always returns its original immutable response.
GetResult/GetSettlement acquire day/race locks to give consistent compound reads.

Result revision pages accept 1–100 records; terminal leg pages accept 1–500. Both use nonnegative
offsets and optional next_offset. Terminal rows identify original coupon UUID and zero-based
leg ordinal, outcome, amount and optional settlement UUID. Settlement summaries can still grow
with the player count; only leg-level and revision reporting is bounded.

## Scratches and voids

The day must be RUNNING or FINALIZING. Scratches/voids accept SCHEDULED, BETTING_OPEN,
BETTING_CLOSED and RESULT_PROPOSED races. Verified, settled and voided races reject ordinary
changes. Horse identities must belong to the immutable original roster. A distinct command for
an already scratched horse rejects; exact completed-key replay returns the original acknowledgement.

RefundPlanner receives all reconstructed purchased coupons and prior terminal resolutions.
Historical wall/box legs are never expanded from today's eligible roster. A five-horse Exacta
1/* has four concrete legs: scratching one possible second horse refunds one leg; scratching
horse 1 refunds every remaining leg. Overlapping box scratches refund only previously active legs.

Each refunded leg gets its full original stake, a terminal REFUNDED resolution and one LEG_REFUND
movement to its original owner. Original coupons, eligibility at purchase, selections, prices,
leg identities and print jobs remain immutable. No replacement ticket or reprint is automatic.

VoidRace refunds all remaining active legs across all pools, including after partial scratches,
then sets VOIDED. Scheduled races without bets can be voided. All pools reach zero active
stake/units. The narrow core correction clears a selected proposal on void; proposal objects
and persisted revisions remain unchanged. A new scratch invalidates a selected proposal and
returns BETTING_CLOSED, as prescribed by the existing core policy.

Accepted stake stays historical. AccountProjector increases refundedStakes, so qualifying turnover
is acceptedStakes - refundedStakes. Promotional unlocking remains a core single-balance policy;
there is no backend unlocking calculation or separate freebet wallet.

## Transactions, locks and incremental reductions

AdministrationCommands reserves durable idempotency before business locks. Every command executes
inside that reservation's Spring transaction. Global lock order is race-day state, race state,
affected player accounts sorted by UUID text, pools sorted by bet-type name, then child records.
Settlement locks every player who purchased a coupon, including players without winnings.
The day lock currently serializes event financial operations.

Refunds load authoritative coupon history under the race lock, lock affected accounts and all
four pools, load existing resolutions and compare each stored pool to PoolSnapshot.aggregate.
The core RefundPlan then determines all new effects. Persistence batches terminal resolutions,
groups credits per player, applies each movement sequentially through AccountProjector, and
appends each player's complete resulting snapshot once. AccountRepository reconstructs the full
ledger and checks the stored projection; balance-only writes are prohibited.

PoolRepository.applyRefundDelta consumes only a core RefundPlan and an expected pool version.
It groups atomic reductions by bet type, checks unit/stake consistency and underflow, advances
only affected pool versions, batch-updates surviving selection counts and deletes only selections
that reach zero. Selection writes compare their original counts. No full selection-table
replacement occurs. Checked arithmetic and SQL constraints prevent negative quantities.
Remaining projections must again match all active concrete legs before the race state is saved.
Any error rolls back resolutions, credits, reductions, scratches and the idempotency reservation.

## Proposals and verification

SubmitResult requires closed betting and BETTING_CLOSED/RESULT_PROPOSED. Core ResultValidator
checks nonempty, distinct eligible original-roster finishers and NORMAL kind. Provisional results
may contain one or two finishers. A correction inserts a new immutable revision; it never overwrites
the previous proposal. Only the selected revision can be verified.

VerifyResult builds an identical in-memory VERIFIED result and calls RaceLifecyclePolicy.
Normal verification requires at least three distinct eligible finishers. The transactional order is:

1. Load the old consistent proposed race/result.
2. Validate the verified transition in memory.
3. RaceRepository.update writes RESULT_VERIFIED while its old-state reconstruction still sees PROPOSED.
4. ResultRepository.verify changes the selected proposal row's status.
5. Reload race state and check its verified result before commit.

Do not reverse steps 3 and 4: update rereads the old state, which requires a PROPOSED result.
No intermediate state is committed. A SQL-injected failure between these writes is tested.
Dead heats, exceptional placings, disqualifications and administrative reversals remain unsupported.
Exceptional result requests receive FAILED_PRECONDITION with an explicit unsupported description.

## Four-pool settlement and reconciliation

SettleRace requires RESULT_VERIFIED and a RUNNING/FINALIZING day. It loads exactly four frozen
rules/snapshots, complete immutable coupons and prior resolutions. PoolSnapshot.aggregate must
match stored units and valid stake, excluding refunds. RaceSettlementPlanner rejects any active
leg referencing a scratched horse. It validates the official NORMAL result and computes every pool:

- P = active valid stake; h = house take basis points; W = winning units.
- D = floor(P * (10000-h) / 10000).
- Dividend = floor(D/W) for W > 0, otherwise zero.
- Each winning leg pays units * dividend; distributed = W * dividend.
- House allocation = P - distributed.

Core uses checked integer money arithmetic and a wide intermediate for the take calculation.
No winning stake is returned separately. No-winner pools use HOUSE_RETAINS_POOL.
Zero and 100% take are supported. A zero-value winning outcome remains WON with a matching
zero WINNINGS movement and payout. Winner selection uses official first, first two unordered,
first two ordered and first three ordered finishers for Win, Quinella, Exacta and Trifecta.

The reference P=1001, h=1500, W=3 yields D=850, dividend=283, distributed=849, house=152.
Core and PostgreSQL gRPC tests assert these exact values.

SQL sequencing is header plus four pool settlements, batched WON/LOST resolutions, grouped player
credits/projections, matching payout records, reconciliation, then SETTLED state and durable response.
Payout foreign keys/triggers require the exact winning resolution and credited movement first.
Previously refunded legs remain REFUNDED; all remaining legs become WON or LOST.
Frozen pool stake and selection units do not change merely because settlement completed.

Checks compare stored terminal outcomes with the complete plan, payout count/totals with credits,
stored pool settlements/versions with the plan, and reconstituted account projections with ledgers.
For every pool, P = distributed + house. Historical original stake = refunds + distributed + house
is checked through FinancialReconciliation. Any failure rolls back the whole four-pool settlement.

## Concurrency, replay and recovery

Both purchases and scratches lock the same day/race state. A committed purchase appears in the
subsequent refund scan; a committed scratch advances eligibility/version and invalidates old quotes.
Overlapping scratches and scratch/void races cannot credit a terminal leg again. Verification
winning the race lock freezes ordinary refund/correction operations; a scratch winning it clears
the proposal and forces revalidation.

The durable fingerprint covers the complete Protobuf request, including expected versions, identities,
ordered finishers and settlement UUID. INSERT ON CONFLICT waits for competing same-key requests;
identical requests replay stored response bytes, changed requests reject ALREADY_EXISTS.
A rolled-back command leaves no completed record. Replays remain valid after later state changes.
A separate key cannot bypass phases, unique terminal resolutions/credits or pool settlement uniqueness.

Crashes before commit leave no partial financial effects; crashes after commit allow acknowledgement
replay. Printing stays outside the financial transaction and original dispatch history is untouched.
Tests use separate connections with barriers/latches, and SQL-trigger failure injection after
refund credits, between verification writes, and during payout insertion.

Malformed requests/results use INVALID_ARGUMENT, absent records NOT_FOUND, illegal phases and
unsupported policies FAILED_PRECONDITION, stale versions ABORTED, duplicate identities/fingerprints
ALREADY_EXISTS, and invalid persisted financial state INTERNAL. SQL details are never returned.

## Performance and remaining work

Coupons reconstruct once per race scan, not once per terminal leg. Terminal inserts and selection
deltas are batched; account append occurs once per credited player. Reconciliation scans complete
race history. There is no new per-leg coupon reconstruction or full pool selection replacement.

Full ledger reconstruction remains mandatory. AccountProjector sequential movement application
copies/scans history, and AccountSnapshot.reconstitute replays it: a very large number of credits
to one player can still have quadratic CPU/allocation cost inside existing core APIs.
Account movement and payout SQL currently uses individual inserts/validation queries. These remain
potential bottlenecks for large winning populations; changing the core snapshot contract needs
separate design and measurement. The 9,900-leg regression measured scratch plus settlement at 3,323 ms in the focused local run
(198 refunded legs; 9,702 settlement outcomes; purchase time excluded). This is a local regression
measurement, not a throughput guarantee.

V1–V4 migrations remain unchanged; no V5 or redundant tables are needed. Existing deferred settlement
and idempotency triggers remain active. The current service is unauthenticated plaintext on loopback.
Caller UUIDs are identifiers, never proof of identity. Production TLS and distinct cashier, event
administrator and result-verifier permissions remain Phase 7 work.

Phase 6 can add final financial reporting, promotional forfeiture execution, final withdrawals and
race-day COMPLETED/CLOSED commands. None are exposed by Phase 5. Physical printers and exceptional
result reversal/settlement policies remain outside this implementation.

## Validation record (2026-11-10)

All five modules passed clean build with configuration/build cache disabled. Core's 34 tests
actually executed in that build; a subsequent standalone core:test was up-to-date.
The final standalone backend:test executed 85 tests against PostgreSQL 18 Testcontainers:
63 existing tests, two persistence boundary/delta tests and 20 Phase 5 gRPC/concurrency scenarios.
There were zero failures, errors or skipped tests. The final 9,900-leg measurement was 3,232 ms.
Explicit contracts:build and backend:bootJar commands also passed (bootJar was up-to-date after
its successful creation in clean build). Core consumer compatibility compilation passed.

Commands used the checked-in gradlew.bat, --no-configuration-cache, --max-workers=1 and
-Pkotlin.compiler.execution.strategy=in-process. Clean build additionally used --no-build-cache.
No permanent JVM settings or dependency versions changed. Initial overlapping build attempts
produced a daemon crash/compiler failures; serial clean validation resolved them.
Local validation logs are retained under build/phase5-validation (ignored build output).
