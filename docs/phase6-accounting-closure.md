# Phase 6 accounting and closure

RaceDayService adds CompleteRaceDay and CloseRaceDay using DayCommand. AccountService
adds GetAccount, GetWithdrawalQuote, CashOut, ListAccountMovements and GetCashoutReceipt.
All RPCs are unary in the existing v1 package; prior fields and methods are preserved.

## Authoritative completion

CompleteRaceDay requires FINALIZING and the expected day version. The day lock is held
through definition/state reconstruction, proof, core transition and optimistic update.
Every defined race must be SETTLED or VOIDED, including races without purchases.
A NOT EXISTS anti-join finds purchased legs lacking terminal resolution. This explicit,
database-computed set is passed to RaceDayLifecyclePolicy.transition; no default is used.

For settled races the selected verified result is validated against the roster/scratches.
Exactly one settlement with all four pools must match that revision. Core pool calculation
is rerun from reconciled pool projections and compared with every stored amount/version.
SQL aggregates concrete nonrefunded legs by canonical selection and checks the pool units.
Each leg outcome and amount must agree with the winning selection/dividend. Refunds must
equal stake and have a matching ledger credit; wins must have matching payouts and ledger
credits; losses cannot have payouts. Unexpected winnings/refund records and missing purchase
debits reject completion. Voided races require no settlement, only refunded legs, and zero
pool stake/units. Missing terminal evidence rejects; stored contradictions report INTERNAL.
No coupon selections are rebuilt and no race is automatically voided.

## Accounts and promotional eligibility

Account queries lock day then account and use AccountRepository.load, which replays the
entire ordered ledger, validates effective opening funding (including V3 adjustments),
projection totals, account version and cashed_out. Reporting returns immutable protobuf values.
Movement history uses identity sequence order and an exclusive after_sequence cursor,
with page sizes 1–100. Timestamps are UTC ISO strings; optional coupon, leg and settlement
references retain their original identities.

There is one combined balance. With opening paid C, promotional F, stakes S, refunds R,
winnings W and balance B, core WithdrawalPolicy computes:

```text
T = S - R
U = min(F, T)
withdrawable = min(B, C + U + W)
forfeitable = B - withdrawable
```

Winning money is withdrawable. Refunded wagers reduce turnover; losing wagers still count.
All amounts are nonnegative Long currency units; core uses integer arithmetic and overflow
checks. The core tests include all five specified examples and extreme values.

GetWithdrawalQuote requires COMPLETED and an account without terminal cashout. It calls
WithdrawalPolicy.quote, returning the account information, unlocked amount, withdrawal,
forfeiture, and current day version. Account version is in the nested account information.
This is informational and reserves nothing.

CashOut accepts identities, expected day/account versions and an idempotency key.
It locks day then account, checks versions and eligibility, calls WithdrawalPolicy.quote
and AccountProjector.cashOut, then appends using AccountRepository.append. No client
amounts are accepted. FINAL_WITHDRAWAL and PROMOTIONAL_FORFEITURE both persist, even when
either or both amounts are zero. The pair advances account version exactly once.
Projection balance becomes zero and cashed_out becomes true; funding/stakes/refunds/winnings
remain unchanged. All records and the original protobuf acknowledgement commit together.

GetCashoutReceipt reconstructs original amounts from the terminal ledger pair after validated
account replay. It never recalculates withdrawal from the zero balance. The stable reference
is day UUID/player UUID. Both movements share the original microsecond cashout timestamp.
Unfinalized accounts have no receipt. Completed accounts cannot obtain another quote or
cashout under a fresh key.

## Closure, transactions and replay

CloseRaceDay requires COMPLETED and its expected version. Under the day lock it enumerates
player_entry, checks orphan projections, locks accounts in sorted UUID order and reconstructs
every ledger. Reconstitution proves exactly two final terminal records, their core-authorized
amounts, no later movements, zero balance, projection flag and single-step version.
Only after every player passes does core transition receive accountsCashedOut=true and the
complete current race state list. No players is vacuously finalized if there are no orphan
accounts; database foreign keys also prevent orphan movements. Zero balance alone never
satisfies closure. Pending/retrying print jobs do not block closure and remain dispatchable.

AdministrationCommands fingerprints every request field and stores original response bytes
through durable command idempotency. Matching retries replay the exact original phase,
version, amounts and timestamp, even after CLOSED or restart. Changed requests under one
key conflict. Reservations, ledger/projection changes and day updates roll back together.
Unique terminal movement indexes and core replay guard duplicates.

Settlement, void, completion, cashout and closure all serialize on the day lock. Cashouts
cannot race past closure. The first lock holder either establishes a valid final state or
rejects; the next observes committed state. Child lock order remains day, races, sorted
accounts, deterministic pools, financial children. Independent-transaction tests use barriers.
A database-trigger failure on forfeiture insertion verifies rollback of the first movement,
projection and reservation; outer transaction failures cover day completion and closure.

## Schema and operational limits

V1–V4 are reused unchanged; no migration, withdrawal table or physical-payment ledger is
needed. Terminal movement uniqueness and durable response storage already exist. Core
production code is unchanged.

Authoritative reads still replay full account histories, and closure replays all players
while holding the day lock. Completion aggregates concrete legs in SQL and reads distinct
pool selections, avoiding coupon expansion, but multiple per-race/pool queries remain.
No scalability claim is made without measurement; future snapshots/batching must retain
validated reconstruction and accounting invariants.

CashOut registers final accounting amounts. A committed receipt does not prove physical
money was delivered. Real disbursement needs an operational protocol and later integration.
Default plaintext loopback 127.0.0.1 is preserved. Player IDs and cashier/POS metadata are
not authenticated identities. Phase 7 must address TLS, authentication, role-based authorization,
trusted cashier identity, financial audit/operating controls, least-privilege database roles,
and the actual monetary disbursement protocol before untrusted-network deployment.

## Change inventory

| File | Change |
| --- | --- |
| tiket-contracts/.../account.proto | New five-method AccountService, typed balances, quotes, receipts and movement pages |
| tiket-contracts/.../administration.proto | CompleteRaceDay and CloseRaceDay RPCs |
| application/AccountApplicationService.kt | Locked authoritative account queries, quote, cashout and receipt reconstruction |
| application/RaceDayApplicationService.kt | Completion/closure proofs and explicit core transition arguments |
| persistence/FinalAccountingProof.kt | SQL terminal evidence, pool reconciliation and all-account closure proof |
| persistence/AccountRepository.kt | Sequence-paginated movement metadata and terminal receipt query |
| grpc/AccountGrpcService.kt | Thin AccountService adapter |
| grpc/AdministrationGrpcServices.kt | New lifecycle adapters and WITHDRAWAL_PROHIBITED status correction |
| Phase6Tests.kt | PostgreSQL 18 generated-client integration, corruption, rollback, restart and contention tests |
| README.md, backend-architecture.md, backend-persistence.md | Phase 6 behavior and documentation links |
| phase6-accounting-closure.md | Accounting protocol, evidence, operational limits and this inventory |

Kotlin backend paths above are relative to the existing backend package; contracts paths
retain pl/umamusume/tiket/api/v1. No build configuration, core implementation, applied
migration, original coupon, settlement, resolution or print definition was changed.

## Validation

The clean five-module build runs with Java 25, the existing wrapper and
`--no-configuration-cache --no-build-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`.
The PostgreSQL 18 regression reports contain 34 core tests and 100 backend tests:
zero failures, errors or skipped tests. Backend totals include the existing 85 plus
15 Phase 6 tests. The clean run executes tests rather than restoring cached results.
The explicit core test, contracts build, backend test and backend bootJar commands
also succeed; unchanged outputs in follow-up invocations are up to date.

Phase 6 covers a two-race/two-player generated-gRPC lifecycle with box, wall and normal
purchases, scratch refunds, all-four-pool settlement, another race voided, completion,
quotes, cashouts and closure. A second backend context reconstructs both original receipts,
zero balances, settlement and CLOSED state from the same database. A pending console job
dispatches after closure. Separate cases exercise all nonterminal race phases, no players,
no purchases, promotional forfeiture, fully lost zero balances, missing terminal evidence,
incomplete settlements, projection corruption, exact replay, version conflicts, failure on
the second terminal insert, outer rollback of completion/closure, and independent-connection
contention with barriers/latches. Same-key and distinct-key cashout/closure races are covered.
