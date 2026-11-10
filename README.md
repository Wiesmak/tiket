# Tiket

A Gradle multi-project monorepo for a parimutuel horse-betting system.

| Module | Responsibility | Project dependencies |
| --- | --- | --- |
| tiket-core | Deterministic Kotlin business logic, ordinary library JAR | None |
| tiket-contracts | Protobuf messages and generated Java gRPC stubs, ordinary library JAR | None |
| tiket-backend | Spring Boot executable, Netty gRPC, JDBC and Flyway | core, contracts |

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

These are alternative launch commands. The application starts only a lifecycle-managed Netty
gRPC server, with no HTTP application server. Stop it normally with IntelliJ Stop or Ctrl+C.

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
Administration and enrollment application services/RPCs are implemented in Phase 3. Printing,
authentication, REST, gRPC-Web and frontend remain absent. See [persistence design](docs/backend-persistence.md) for boundaries and usage.
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
**Administrative endpoints have no authentication or authorization. Keep plaintext on loopback until
production authentication, authorization and TLS are implemented.** Purchases and settlement remain deferred.
