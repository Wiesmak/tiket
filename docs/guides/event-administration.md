# Event administration

## Contracts

The v1 API and Java package are preserved. All calls are unary; SystemService.Ping is unchanged.

| Service | RPC | Meaning |
| --- | --- | --- |
| RaceDayService | CreateRaceDay | Atomically insert a complete DRAFT definition, races, ordered runners, four rules and empty pools |
| RaceDayService | GetRaceDay | Complete immutable definition and current event status |
| RaceDayService | PublishRaceDay | DRAFT to READY, publishing the complete definition |
| RaceDayService | StartRaceDay | READY to RUNNING, freezing enrollment and opening funding |
| RaceDayService | FinalizeRaceDay | RUNNING to FINALIZING after all betting windows close |
| RaceService | ListRaces / GetRace | Immutable configuration and operational status |
| RaceService | GetRaceStatus | Operational phase and version |
| RaceService | OpenBetting | SCHEDULED to BETTING_OPEN subject to the event-wide open limit |
| RaceService | CloseBetting | BETTING_OPEN to BETTING_CLOSED |
| EnrollmentService | EnrollPlayer | Enroll in DRAFT or READY with zero or nonnegative paid/promotional opening funding |
| EnrollmentService | AddOpeningFunding | Increase paid and/or promotional opening funds before start and before any activity |
| EnrollmentService | GetEnrollment / ListPlayers | Effective opening amounts, current balance, account and opening versions |

Creation supplies UUIDs, strict ISO date, display number 1–999, IANA time zone, currency, rules version,
positive coupon expansion and simultaneous-open limits, HOUSE_RETAINS_POOL, and a nonempty ordered race list.
Each race supplies UUID, number, course, title, ordered runners (horse UUID, program number, name), and
exactly one WIN, QUINELLA, EXACTA and TRIFECTA pool with positive integer stake and 0–10000 take basis points.
Program numbers are strings, retaining presentation such as leading zeroes. Horse identities can recur
in separate races. Identifiers are supplied by callers and retained.

Definitions are one-shot, including during DRAFT. There is no draft replacement/edit endpoint.
A future editing workflow needs separately designed versioned immutable definitions.
Publication and start reconstruct the complete definition and authoritative race states and call core.
Finalization can leave scheduled or closed races for later resolution; it cannot reopen betting.

Completion and closure are available after financial proof; see [cashout](cashout.md). See [authentication](../api/authentication.md) for current security.

## Transactions and replay

Thin gRPC adapters delegate to RaceDayApplicationService, RaceApplicationService and
EnrollmentApplicationService. Core owns lifecycle and enrollment decisions. JDBC repositories remain
storage primitives. Every command runs inside the existing idempotency transaction and rolls back
its reservation and business effects together on failure.

All existing-day commands acquire the race-day state lock first, followed by target race, accounts
in player UUID order, pools in bet-type order, then subordinate rows as applicable. Queries acquire
day/account locks to obtain consistent multi-query snapshots. Creation relies on unique identities
inside one transaction. Event start is serialized against enrollment and funding. Opening reads every
race state while holding the event lock, enforcing configured simultaneous-open limits.

Day commands require expected event version. Betting commands require expected day and race versions.
Enrollment requires expected day version. Funding requires expected account version. Versions are
compared after locking; stale commands are rejected without automatic retry.

Every mutation carries a nonblank idempotency key, scoped by operation and event (plus race/player
where relevant). SHA-256 covers the typed Protobuf request bytes: known fields have stable field order,
there are no maps, and repeated configuration/roster order remains significant. All request fields,
including expected versions, amounts and identities, participate. Identical retries replay the
original acknowledgement. The existing TEXT result_reference stores Base64 serialized acknowledgement
bytes, so replay never reloads a later phase or balance. Retain compatibility with these response schemas
when evolving the API. Concurrent identical requests wait on the unique reservation; only one mutates.
Changing request contents under the same key returns ALREADY_EXISTS, also used for duplicate identities.

Malformed structures, unsupported enum values, UUIDs, dates, zones, amounts and overflow return
INVALID_ARGUMENT. Missing records return NOT_FOUND; illegal lifecycle/frozen funding returns
FAILED_PRECONDITION; stale versions and recognized database concurrency failures return ABORTED.
Unexpected failures return INTERNAL with a sanitized message and server-side logging.

## V3 persistence

V1 and V2 remain unchanged. V3 adds nonnegative unique per-event race ordinals, backfilling existing
races deterministically by display number. New complete definitions save caller list positions;
display numbers remain separate. Runner ordinals are unchanged.

Original player_entry records remain immutable. Append-only opening_funding_adjustment records carry
event/player, consecutive positive opening_version, nonnegative paid/promotional increments with
positive combined value, and creation timestamp. The insert trigger locks the event before the account,
rejects started days, existing movements/wagers or cashout, and checks the next opening version.
Deferred checks reconcile adjustment count and inactive balance with effective opening amounts.
account_balance.opening_version begins at zero and advances with account version for each adjustment.
A dedicated conditional projection update and adjustment insert commit atomically.

PlayerEntryRepository folds the original amounts and adjustments using checked Money arithmetic.
AccountRepository supplies openingVersion to AccountSnapshot.reconstitute and preserves it when
appending later ledger movements. Funding creates no ordinary financial movement and no separate
paid/promotional wallets. The database ledger and core still reconcile financial activity.

Runtime roles must not own tables or have DDL/trigger-disabling privileges; use separate migration
credentials in production. Account history replay and complete enrollment lists are currently
unbounded and should be measured before large deployments.