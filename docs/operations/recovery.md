# Operations and recovery

## Database privileges, limits and shutdown

Use ordinary PostgreSQL JDBC configuration; no CNPG runtime dependency exists. Set sslmode=verify-full and sslrootcert in DB_URL for production certificate/hostname validation. Credentials and client certificates belong in external secrets. Use standard Flyway user/password overrides for migration credentials; the runtime role needs DML/SELECT on application tables and sequences, not schema ownership or DDL. Migration identity owns migrations and trigger creation. Grant audit/actor INSERT/SELECT only to runtime; deny mutation privileges as defense in depth. Never use a superuser.

Pool defaults: 10 connections, 5-second acquisition wait. JDBC connect/socket timeouts are bounded in the default URL; a replacement DB_URL must carry suitable timeouts. TransactionTemplate has a configurable 20-second timeout. PostgreSQL role-level statement_timeout and lock_timeout are recommended to bound server work too.

Inbound messages default to 1MB and metadata to 8KB. The existing expanded coupon-leg maximum remains authoritative; no truncation is introduced. A per-process semaphore caps concurrent calls at 32. Authenticated subjects share a bounded per-second limiter, default 60 requests/second, at most 4096 tracked subjects per window. Unknown/full windows reject with RESOURCE_EXHAUSTED. These are per-replica limits, not cluster-wide rate enforcement. Client deadlines should exceed normal transaction time and stay within platform termination budgets. A response lost after commit must be recovered with the same request/key.

Spring shutdown allows 25 seconds for gRPC and scheduled work within a 30-second lifecycle phase. Context closure stops accepting new requests and stops new print claims. In-flight attempts complete, or their durable leases expire for recovery. Set platform termination grace beyond all application shutdown phases. Financial acknowledgement remains after transaction commit. Shutdown under sustained in-flight load still needs dedicated validation.

## Multi-instance behavior and recovery runbooks

Financial authority remains PostgreSQL: day/race/account/pool row locks, durable unique command reservations, immutable evidence and optimistic versions. No local limiter/cache replaces it. Existing concurrency tests use independent connections and preserve lock ordering. Printing claims use SKIP LOCKED and leased owners; expired leases may cause duplicate physical output, never another purchase debit. MultiInstanceSecurityTests runs two complete secured backend contexts against one PostgreSQL 18 database, verifies concurrent exact purchase and cashout replay, replay after event closure, different-actor denial and expired print-lease ownership. This is a two-context same-host test, not a distributed throughput measurement.

* Stuck printing: inspect print_job status, lease_until and print_attempt history read-only. Wait for an active lease; expired claims recover through normal dispatch. After max attempts, investigate printer availability before an authorized explicit ReprintCoupon with a new command key. Duplicate output is possible after uncertain delivery.
* Projection inconsistency: stop affected event mutations, preserve a database backup and authoritative movements/coupons/resolutions/settlements. Reproduce with existing read-only reconstruction/proof paths. Do not update balances or financial history automatically.
* Idempotency conflict: correlate operation/scope and stored request fingerprint with the operator's original request. Retry identical bytes and key only after checking authority. Never invent a new identity to evade a failed payment-like command.
* Unexpected transaction failure: inspect bounded status, audit correlation, PostgreSQL logs and constraints. Determine whether commit occurred by authorized exact replay. Do not assume a timeout proves rollback.
* Database outage: remove unready instances from traffic, keep processes alive, restore connectivity and observe readiness. Do not run business retries with new keys. Pool reconnects normally when the database recovers.
* Incomplete finalization: inspect race terminal states, four-pool settlement evidence, terminal movements and outstanding cashouts. Complete missing legitimate domain operations; do not force event state or rewrite proof evidence.

## Performance and residual validation gaps

Account reconstruction still replays the authoritative ledger. Day-level locks still serialize financial commands within an event. Existing large-coupon, scratch, settlement, final proof and contention tests remain repeatable regression scenarios, including the 9,900-leg purchase. No throughput or lock-wait claim is made. PersistenceTests.authoritativeLedgerScalingBaseline now measures repeated authoritative reads after 3 warmups and 10 samples at each ledger size. A Windows/JDK 25/PostgreSQL 18 Docker Desktop run measured:

| Movements | Median wall time (ms) | Median thread allocation (bytes) |
| --- | --- | --- |
| 0 | 3.005 | 6,024 |
| 10 | 3.056 | 29,592 |
| 100 | 3.235 | 760,272 |
| 500 | 6.539 | 16,515,552 |

Thread CPU timing resolved to zero for these short reads on this host; it is not evidence of zero CPU work. The allocation growth warrants a separate reconstruction-profile investigation. SQL time, lock waits and end-to-end production scaling were not isolated by this harness. No speculative financial optimization was introduced. A compatibility-safe future improvement should investigate a validated one-pass internal reconstruction builder and prove equivalence against the immutable projector; persisted snapshots must not replace authoritative evidence without a separate design and migration review.

Repeat with `gradlew.bat :tiket-backend:test --tests "*PersistenceTests.authoritativeLedgerScalingBaseline" --rerun-tasks --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`. Results appear as PERF_LEDGER lines in the JUnit XML report.

Remaining scope includes all-role positive RPC matrix tests, shutdown under sustained in-flight financial work, durable print trace propagation, full query/lock/CPU profiling of the large settlement and event-closure workloads, and stronger evidence for cluster-wide deployment behavior. JWT revocation before expiration requires an external policy. There is no claim of cross-host scalability. JWKS unknown-key/rotation and two-context recovery have dedicated Netty integration tests; mTLS has dedicated certificate/JWT separation tests. These gaps prevent a general production-readiness claim.

## Backup and restore

The platform owner must configure PostgreSQL backups, WAL retention, encryption, access controls and restore drills. Choose RPO/RTO explicitly. Back up schema, immutable history, command responses, audit and outbox consistently; projections alone are insufficient. Restore into an isolated database, validate Flyway checksums, run reconciliation/read-only proofs and confirm financial state before restoring traffic. Retain command keys to prevent duplicate accounting. Previously dispatched print jobs may have physical effects outside the restored database: reconcile before enabling workers. Database rollback cannot recall cash or paper.
