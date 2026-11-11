# Tiket

Phase 8 deployment entry points: build the backend bootJar, then `docker build -t tiket:kind .`.
Use `bash scripts/test-kind.sh` (Windows: `powershell -File scripts/test-kind.ps1`) for disposable
kind deployment tests. One portable chart lives in `charts/tiket`.
See [deployment and CI operations](docs/phase8-deployment-ci.md) for configuration,
signed OCI releases, actual validation evidence and unresolved release gates.

A Gradle multi-project monorepo for a parimutuel horse-betting system.

See [serial printing](docs/serial-printing.md) for the optional 384-pixel raster adapter and POS routing.

| Module | Responsibility | Project dependencies |
| --- | --- | --- |
| tiket-core | Deterministic Kotlin business logic, ordinary library JAR | None |
| tiket-contracts | Protobuf messages and generated Java gRPC stubs, ordinary library JAR | None |
| tiket-backend | Spring Boot executable, Netty gRPC, JDBC and Flyway | core, contracts, print-api, print-console |
| tiket-print-api | Immutable printer contract, ordinary Kotlin/JVM library | None |
| tiket-print-console | Stdout ticket dispatch | print-api |

Phase 1 preserves the Initializr-generated `TiketBackendApplication` in
`pl.umamusume.tiket.backend`. Its dependency bundles and Spring convention are reused;
Protobuf generation was moved to contracts. There were no nested settings or wrappers to remove.
The root settings, wrapper and version catalog remain shared. `buildSrc/settings.gradle.kts`
only configures Gradle's build logic, not a separate application.

## Requirements and build

Use JDK 25 and the checked-in Gradle 9.7.0 wrapper. Kotlin is 2.4.21, Spring Boot is
exactly 4.1.2, and the Protobuf plugin is 0.9.6. Contracts explicitly configure protoc 4.36.2
and grpc-java generator 1.84.1, aligned with Boot's managed runtime versions.
Backend dependencies use Boot dependency management; contracts use its BOM without applying
the Boot plugin. Only backend applies Boot and produces a bootJar.
Generated sources stay under `tiket-contracts/build` and are ignored by Git.

```shell
./gradlew clean build --no-configuration-cache
./gradlew :tiket-contracts:build
./gradlew :tiket-backend:test --no-configuration-cache
./gradlew :tiket-backend:bootJar --no-configuration-cache
```

CI and publication share `bash scripts/ci-gradle.sh`. To reproduce validation:
`bash scripts/ci-gradle.sh clean build --no-build-cache`.
It sets a 1536 MiB build heap, 1024 MiB metaspace, one worker, in-process Kotlin,
plain console and stack traces, and verifies the limits inside the build JVM.
Normal local Gradle cache and memory defaults are unchanged.

On Windows use `gradlew.bat`. On machines with limited Windows commit memory or
Kotlin daemon socket failures, append `--max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`.
Shared test JVMs use a 64 MiB initial / 256 MiB maximum heap and two active processors.
Protobuf 0.9.6 generation tasks are explicitly marked incompatible with Gradle's configuration
cache; Gradle may discard that cache for builds involving those tasks. The build cache remains usable.

Docker must be running for PostgreSQL Testcontainers tests.
The Phase 2 persistence suite requires Docker and fails explicitly if it is unavailable;
database-independent in-process and real Netty transport tests still execute. Integration tests use isolated PostgreSQL 18 containers, start the
application twice against the same database, validate Flyway history, call Ping over
loopback, and check shutdown of the listener and connection pool.

## PostgreSQL and local startup

Create a PostgreSQL database and a role allowed to run Flyway migrations in it. Configure
an existing instance through environment variables; no database credentials are committed.

| Variable | Default / purpose |
| --- | --- |
| DB_URL | `jdbc:postgresql://localhost:5432/tiket?connectTimeout=5` |
| DB_USERNAME | Required database role |
| DB_PASSWORD | Required database password |
| GRPC_ADDRESS | `127.0.0.1`; keep plaintext development on loopback |
| GRPC_PORT | `9090`; use `0` for an ephemeral test port |

Spring's usual `SPRING_DATASOURCE_*` and `SPRING_GRPC_SERVER_*` property overrides
also work. HikariCP uses a 5-second connection timeout, fail-fast initialization and at most
10 pooled connections. Flyway runs at startup, validates migration checksums and fails
startup if PostgreSQL is unavailable or migration validation fails. Configure a JDBC
`connectTimeout` when supplying a custom URL.

`V1__foundation.sql` is an intentionally table-free migration (`SELECT 1`), creating
Flyway history only. It is not Flyway's `baselineOnMigrate` shortcut. Subsequent startups
validate and reuse its existing history. Do not change applied migration contents.

In IntelliJ, import the root Gradle project, set Project SDK and Gradle JVM to Java 25,
reload Gradle, and run the existing `TiketBackendApplicationKt` main class in
`tiket-backend`. Set DB_USERNAME, DB_PASSWORD and optionally DB_URL in the run
configuration environment. Alternatively:

```shell
./gradlew :tiket-backend:bootRun
java -jar tiket-backend/build/libs/tiket-backend-0.0.1-SNAPSHOT.jar
```

These are alternative launch commands. Business traffic uses the lifecycle-managed native Netty
gRPC server. Actuator uses a separate restricted HTTP management listener; the application HTTP
connector is disabled. Supply JWT_ISSUER, JWT_JWKS_URI and JWT_AUDIENCE for startup outside tests.
Stop normally with IntelliJ Stop or Ctrl+C.

## Ping

With grpcurl installed, run from the repository root (the explicit proto avoids requiring
server reflection):

```shell
grpcurl -plaintext -import-path tiket-contracts/src/main/proto -proto pl/umamusume/tiket/api/v1/system.proto -d '{}' 127.0.0.1:9090 pl.umamusume.tiket.api.v1.SystemService/Ping
```

Expected response: `{"status":"OK","apiVersion":"v1"}`.

## Scope

Phase 2 provides the V2 PostgreSQL schema, concrete JDBC repositories, validated domain
reconstruction, version checks, transaction and locking helpers, and durable idempotency.
The ledger is authoritative; account snapshots replay and reconcile history, while projection
totals support efficient display reads. No core APIs were changed.
Administration and enrollment services/RPCs are implemented in Phase 3. Phase 4 adds typed
betting, atomic purchases and console dispatch through a transactional outbox.
Phase 7 adds JWT authentication and scoped authorization. REST business APIs, gRPC-Web and frontend remain absent. See [Phase 4](docs/phase4-betting-printing.md)
and [persistence design](docs/backend-persistence.md) for boundaries and usage.
Docker is required for PersistenceTests: it deliberately fails rather than silently skipping
the Phase 2 integration suite. Tests cover V1 upgrades, round trips, reconciliation, rollback,
corruption detection and independent-connection concurrency.
See [backend architecture](docs/backend-architecture.md).

# Core library

A standalone deterministic Kotlin/JVM domain library for a preplanned parimutuel horse-betting race day.
Targets Kotlin 2.4.x and Java 25. Runtime dependencies are limited to Kotlin and the JDK.

Implements Win, Quinella, Exacta and Trifecta; structured normal, wall and box selections;
immutable priced coupons; purchase plans; pool settlement; leg refunds; lifecycle policies;
single-balance accounts and promotional withdrawal eligibility.

The core remains independent of backend frameworks, persistence, transport and printing.

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

## Phase 3 administration

Complete race-day creation, publication/start/finalization, betting windows, enrollment and pre-start
paid/promotional funding are available through typed gRPC. See [Phase 3](docs/phase3-administration.md).
Definitions are immutable one-shot creations; draft editing is deferred.
Administrative endpoints require trusted EVENT_ADMIN authority and explicit event scope in Phase 7.
Use the documented direct-TLS or private gateway-h2c mode. Settlement is implemented in Phase 5.

## Phase 4 betting and printing

Quote/purchase normal, wall and box coupons through BettingService. Purchased legs, debit,
incremental pool contributions, exact idempotent response and original print job commit together.
PrintingService provides status and explicit idempotent reprints. Console dispatch runs after
commit with bounded retries and crash recovery; duplicate output is possible after a crash.
Disable the worker with `tiket.printing.enabled=false`. See [Phase 4](docs/phase4-betting-printing.md).

## Phase 5 refunds and settlement

RaceService supports scratches and race voiding with exact active-leg refunds. ResultService supports
immutable proposals, verification, complete four-pool settlement and bounded result/leg history.
All financial effects, incremental pool reductions and idempotent acknowledgements commit together.
See [Phase 5](docs/phase5-refunds-settlement.md) for RPC formats, arithmetic, locks and recovery.
Final accounting and event completion/closure are implemented in Phase 6.

## Phase 6 final accounting

RaceDayService now supports authoritative completion and closure. AccountService provides
validated balances, withdrawal quotes, terminal cashout, movement history and durable receipts.
See [Phase 6](docs/phase6-accounting-closure.md) for financial proofs, replay and limitations.
Registered cashout does not confirm physical payment. Phase 7 adds scoped JWT security, native TLS
and operational monitoring; external disbursement integration remains outside this repository.


## Phase 7 security and operations increment

See [security and operations](docs/phase7-security-operations.md) for the RPC matrix, mandatory JWT claims, event/player/POS authorization, native TLS/h2c, separate management HTTP listener, append-only V5 audit/command-actor schema, resource limits, recovery runbooks and exact Phase 8 deployment contract. Earlier phase statements about absent authentication or HTTP describe historical behavior. Application HTTP is disabled; only operational Actuator HTTP is enabled. Phase 7 residual gaps are explicitly listed and deployment readiness is not claimed.

Development builds: PR evidence includes unsigned JVM/Helm test artifacts, uploaded
before the vulnerability gate. PRs and master snapshots permit **all HIGH findings
with warnings**, including fixable and unknown-status findings; CRITICAL findings,
scanner errors and invalid evidence always block. Official releases block HIGH and
CRITICAL. Development artifacts are **not production-approved**. Trusted master
snapshots require GHCR publication plus verified image/chart signatures.
See [Phase 8 vulnerability policy](docs/phase8-deployment-ci.md#current-development-policy-all-high-findings-warn-2026-11-11)
for the exact policy, full security evidence, historical CVE inventory and signing requirements.
