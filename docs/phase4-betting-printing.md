# Phase 4: betting and console printing

## Contracts and calculations

The v1 BettingService exposes unary QuoteCoupon, PurchaseCoupon, GetCoupon and
ListPlayerCoupons. PrintingService exposes GetPrintStatus and development-only
ReprintCoupon. See betting.proto in tiket-contracts for generated Java message/stub types.

BettingSelection has a oneof positional/box variant. A positional selection is an ordered
list of Position values, each a oneof UUID horse_id or wall=true. A box is an explicit
list of UUID horse_ids. PoolType is the existing typed four-pool enum with an unspecified
zero value. Display program numbers are never identities. Exacta/Trifecta preserve
position order; core canonicalizes Quinella and box identities.

Pricing uses CouponPricingCalculator.quote: K combinations × Q units gives total units;
each leg costs Q × S and total stake is K × Q × S. All amounts are checked Long integer
currency units. Exacta box #2,3,4 with Q=2, S=10 produces six legs of stake 20, twelve
units and debit 120. Core enforces expansion limits before allocating outcomes.

Quote generation locks day then race to read a coherent definition, state, eligibility and
frozen pool rules. Only RUNNING / BETTING_OPEN is accepted. It changes no business records.
The response includes pricing, canonical selection, both state versions and SHA-256
confirmation over deterministic protobuf pricing, fixed-width versions and canonical
ordered expanded outcomes. This is a consistency check, not authorization or a reservation.

Purchase recomputes the authoritative quote under locks, compares confirmation and expected
versions, then passes freshly computed quote.terms to BetPlacementPolicy.purchase. No
public QuoteTerms deserialization or core visibility changes are needed. Changed prices,
eligibility or state cannot silently change a purchase. Stale versions map to ABORTED;
changed confirmations/closed betting/insufficient funds to FAILED_PRECONDITION.
Expansion limit maps to RESOURCE_EXHAUSTED; malformed requests to INVALID_ARGUMENT;
missing records to NOT_FOUND, duplicate identities/keys to ALREADY_EXISTS. SQL errors
are sanitized; unexpected corruption maps to INTERNAL.

## Atomic persistence and queries

AdministrationCommands reserves a durable operation/event/player/key before financial locks.
Purchase lock order is day state, race state, player account, target pool, child records.
CloseBetting uses the same day/race locks. Account snapshots retain full validated movement
replay. BetPlacementPolicy's successful PurchasePlan is the only source of the stake debit.

One transaction stores original expression, every ordered leg, historical eligible roster,
POS/cashier and UTC purchase timestamp; AccountProjector applies the plan debit and the
ledger/projection append; the target pool receives its delta; an ORIGINAL print job is
enqueued; the exact serialized protobuf acknowledgement is saved. Failure rolls back all
of these, including the idempotency reservation.

Idempotency fingerprints canonical structured selections and every request field, including
quote, versions, coupon/player IDs and POS/cashier. Identical concurrent requests wait and
replay the exact committed acknowledgement, including historical balance/version, job ID
and initial PENDING status. Current balance and print state never rewrite that response.
Different content under the same scoped key is rejected, including after process restart.

PoolRepository.acceptPurchase accepts a PurchasePlan, locks only its target pool, validates
delta stake/unit consistency and checked totals, advances version once and batch UPSERTs
canonical selection units. PostgreSQL addition also rejects bigint overflow. The resulting
PoolSnapshot is reloaded and reconciled. Other pools are untouched. Full-state update remains
available for future phases but is unused by purchases. Coupon legs use JDBC batch inserts.

GetCoupon returns immutable original selections and ordered concrete legs with stable
coupon/ordinal identities. Historical reconstruction uses purchase eligibility, never current
scratch state. Player listings require page_size 1–100 and nonnegative offset; next_offset
is present when more records exist. The V4 player index supports ordered listings.

## Printing dependency direction and data

backend -> print-api; backend -> print-console -> print-api.
Both printing modules are ordinary Kotlin/JVM modules sharing the root build. Neither depends
on Spring, core, JDBC, gRPC or hardware. TicketPrinter accepts only immutable validated
TicketPrintData and returns Dispatched or Failed(message,retryable). Exceptions are treated
as retryable dispatch failures.

TicketPrintData fields:

| Field | Meaning |
| --- | --- |
| ticketId | Stable coupon UUID |
| racecourseName | Frozen racecourse label |
| raceDayNumber | Three-digit 001–999 |
| pointOfSaleNumber | Positive purchase POS |
| cashierIdentifier | Purchase metadata, currently unauthenticated |
| betDateTime | Original purchase in event zone, minute precision |
| printDateTime | Claim/attempt start in event zone, minute precision |
| raceNumber | Race number within event |
| betType | WIN, QUINELLA, EXACTA or TRIFECTA |
| unitsPerCombination | Q |
| unitBetPrice | Integer S |
| betString | Original expression with frozen program numbers |
| combinationCount | K |
| totalBetUnits | K × Q |
| totalBetValue | K × Q × S |
| currency | Frozen event currency |
| isReprint | Explicit reprint marker |
| printAttemptId | Unique UUID for each attempt |

Backend mapping uses immutable definition program numbers and the original selection, not
expanded legs or current eligibility. Positional expressions use slash separators and *;
boxes use # with deterministic numeric-then-lexical program ordering. Quinella follows
core canonical identity order, with its explicit horse before a wall. Dates render as
yyyy-MM-dd HH:mm. Reprints preserve purchase time; attempt time changes with each claim.

ConsoleTicketPrinter writes one readable ticket to stdout, checks stream errors and flushes.
Dispatched means stdout accepted the data. There is no physical printing guarantee.

## V4 outbox and worker

V4__printing_outbox.sql leaves V1–V3 unchanged. print_job stores UUID, coupon FK,
ORIGINAL/REPRINT type, status, enqueue/next attempt timestamps, count, owner UUID,
lease expiry and last error. A partial unique index permits one ORIGINAL per coupon.
print_attempt stores a distinct UUID, job FK, start/completion timestamps, result and error.
Indexes support eligible-job scans and attempt history. Reprints use durable command
idempotency; the committed command acknowledgement identifies the single enqueued job.

Transitions: PENDING -> CLAIMED -> DISPATCHED, RETRYING or PERMANENTLY_FAILED.
RETRYING -> CLAIMED when due. Expired CLAIMED jobs are recovered with a new owner and
attempt; old STARTED attempts become LEASE_EXPIRED. Stale owners cannot complete a
reclaimed job. Expiry at the attempt limit permanently fails the job.

The worker uses SELECT FOR UPDATE SKIP LOCKED and commits claim/STARTED attempt before
loading printable data and calling TicketPrinter outside transactions. Completion uses a
separate short transaction. No financial locks are held during stdout. Financial commit is
required before any job is visible to the worker. Printer failures never refund or reverse bets.

Configuration: tiket.printing.enabled (default true), poll-millis (1000),
max-attempts (5), lease-seconds (60). Each poll handles at most ten jobs. Retry delay is
min(300, 2^attempt-count) seconds. Permanent failures stop immediately. Disable scheduling
with tiket.printing.enabled=false for deterministic tests or manual processing.

Dispatch is at least once. A crash after stdout succeeds and before completion commits can
cause duplicate output after lease recovery. Attempts have distinct identities for diagnosis.
Choose a lease longer than expected printer duration; no physical exactly-once protocol exists.

GetPrintStatus reports current job status/count and latest attempt details. ReprintCoupon
requires an existing committed coupon and an idempotency key; it creates a distinct REPRINT
job without any account/pool mutation. Replaying it returns its original enqueue acknowledgement;
query status separately for current progress.

## Limits and next phases

The default server remains plaintext on 127.0.0.1. All financial and printing RPCs are
unauthenticated development APIs. Caller-supplied player/POS/cashier fields are validated
metadata, not authenticated identities. Production identity binding, access controls and TLS
are mandatory before deployment; do not bind to an untrusted interface.

Core APIs and versions are unchanged. Purchase performs bounded expansion in quote/policy
and validated reconstruction; account history replay remains unbounded and can become
expensive (including repeated append validation). Profile realistic ledger and pool sizes
before optimizing; no alternate financial snapshot bypass was introduced. Day locking
serializes purchases across an event, matching existing administration coordination.
Offset pagination is bounded per response but deep offsets can be expensive.

Phase 5 still needs scratch/void administration and atomic refunds/pool reductions.
Phase 6 still needs official results, verification, settlement and payouts. No production
services for those operations, cashout or hardware printers are added here.

## Validation on 2026-11-10

The backend suite passed 63 tests (28 existing plus 35 Phase 4 cases), zero failures or skips,
using real PostgreSQL 18 Testcontainers. Generated gRPC stubs exercise administration through
purchase, historical retrieval and print status. Tests cover four pools, selections/pricing,
quote changes, independent-connection contention, exact replay/restart, rollback, overflow,
paging, console output, post-commit visibility, retries, reprints and expired claims.
A 9,900-leg Exacta box exercises batch persistence and rejects a 10,100-combination request
before expansion under the configured 9,900-leg limit.

The root clean build and explicit contracts/print-api/print-console build, backend test and
backend bootJar targets passed with configuration cache disabled, one worker and in-process
Kotlin compilation. Existing migration assertions now expect four migrations; no migrations
were edited. No core API compatibility changes were required.

All 33 existing core tests also passed in an explicit uncached rerun. That forced compilation
exhausted the reused daemon's metaspace on its first attempt; a fresh single-use daemon with
command-local -Xmx768m and -XX:MaxMetaspaceSize=512m completed successfully. Repository JVM
settings were preserved. Total verified tests: 96, with zero failures or skips.
