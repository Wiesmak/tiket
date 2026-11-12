# Final accounting and cashout

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

Authoritative reads still replay full account histories, and closure replays all players
while holding the day lock. Completion aggregates concrete legs in SQL and reads distinct
pool selections, avoiding coupon expansion, but multiple per-race/pool queries remain.
No scalability claim is made without measurement; future snapshots/batching must retain
validated reconstruction and accounting invariants.

CashOut registers final accounting amounts. A committed receipt does not prove physical
money was delivered. Real disbursement needs an operational protocol and later integration.