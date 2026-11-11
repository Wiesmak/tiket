# Phase 7 security and operations

Implementation status: application hardening increment. See residual gaps and validation evidence below; this document is not a production-readiness certification.

## Reviewed RPC authorization policy

Every row additionally requires explicit event scope except anonymous Ping. READ means any recognized application role. ACCOUNT means CASHIER or AUDITOR, or PLAYER with an explicit player binding matching the resource. EVENT_ADMIN does not imply financial read permission. Cashiers require an explicit allowed-player scope for account operations and allowed POS scope for coupon operations. Unknown methods are denied.

| Service | RPC | Roles/capability |
| --- | --- | --- |
| RaceDayService | CreateRaceDay | EVENT_ADMIN |
| RaceDayService | GetRaceDay | READ |
| RaceDayService | PublishRaceDay | EVENT_ADMIN |
| RaceDayService | StartRaceDay | EVENT_ADMIN |
| RaceDayService | FinalizeRaceDay | EVENT_ADMIN |
| RaceDayService | CompleteRaceDay | EVENT_ADMIN |
| RaceDayService | CloseRaceDay | EVENT_ADMIN |
| RaceService | ScratchHorse | EVENT_ADMIN |
| RaceService | VoidRace | EVENT_ADMIN |
| RaceService | ListRaces | READ |
| RaceService | GetRace | READ |
| RaceService | GetRaceStatus | READ |
| RaceService | OpenBetting | EVENT_ADMIN |
| RaceService | CloseBetting | EVENT_ADMIN |
| EnrollmentService | EnrollPlayer | CASHIER |
| EnrollmentService | AddOpeningFunding | CASHIER |
| EnrollmentService | GetEnrollment | ACCOUNT |
| EnrollmentService | ListPlayers | AUDITOR |
| BettingService | QuoteCoupon | CASHIER,PLAYER |
| BettingService | PurchaseCoupon | CASHIER |
| BettingService | GetCoupon | ACCOUNT |
| BettingService | ListPlayerCoupons | ACCOUNT |
| PrintingService | GetPrintStatus | CASHIER |
| PrintingService | ReprintCoupon | CASHIER |
| ResultService | SubmitResult | RESULT_SUBMITTER |
| ResultService | VerifyResult | RESULT_VERIFIER |
| ResultService | GetResult | READ |
| ResultService | ListResultRevisions | RESULT_SUBMITTER,RESULT_VERIFIER,AUDITOR |
| ResultService | SettleRace | SETTLEMENT_OPERATOR |
| ResultService | GetSettlement | AUDITOR |
| ResultService | ListRaceLegResolutions | AUDITOR |
| AccountService | GetAccount | ACCOUNT |
| AccountService | GetWithdrawalQuote | ACCOUNT |
| AccountService | CashOut | CASHIER |
| AccountService | ListAccountMovements | ACCOUNT |
| AccountService | GetCashoutReceipt | ACCOUNT |
| SystemService | Ping | PUBLIC |

Race-day, race, player, coupon, settlement and print-job IDs are untrusted resource selectors. Cashier, POS and idempotency fields are not credentials. Nested commands, definitions and account/race queries must be inspected as well as top-level fields. Print jobs resolve through coupons before disclosure. Coupon reads resolve stored event, owner and POS. Settlement queries remain event-bound and repository validation checks race/settlement membership.

## Authentication and trusted claims

Native Spring gRPC AuthenticationProcessInterceptor authenticates bearer access tokens using Spring Security Resource Server and NimbusJwtDecoder. No third-party gRPC starter is used. JWT configuration is mandatory outside the isolated test profile, including on h2c. Configure JWT_ISSUER, JWT_JWKS_URI and JWT_AUDIENCE. Issuer/JWKS must be HTTPS. Only RS256 is accepted; unsigned, HMAC and other algorithms are rejected. Signature, issuer, expiration, nbf, audience and nonblank subject are checked. The standard validator allows its normal 60-second clock skew. Expiration is mandatory. Nimbus retrieves and caches JWKS and supports refresh for signing-key rotation. The server has no local production signing key.

Claims are issued by the trusted issuer, not copied from gRPC metadata:

| Claim | Shape and meaning |
| --- | --- |
| sub | Stable nonblank subject; never implicitly a PlayerId |
| tiket_roles | Array of exact role names from the RPC policy; unknown roles grant nothing |
| tiket_events | Array of exact race-day UUID strings; no wildcard |
| tiket_player | Explicit UUID string binding for PLAYER self-access |
| tiket_players | Array of player UUID strings a cashier may service |
| tiket_pos | Array of allowed POS numbers encoded as strings |
| tiket_cashier | Exact operator identifier required in purchase requests |

Each claim name can be changed through properties such as `tiket.security.claims.tiket_roles=roles`. Claim values still use the documented shape. Arrays are not inferred from space-delimited strings or arbitrary nested vendor roles. Configure the identity provider's token mapper accordingly. There is no implicit administrator bypass for event or cashier scope.

Historical coupon metadata remains unchanged. Purchase rejects mismatching cashier/POS fields. Print-job queries resolve their coupon before returning data. Missing and forbidden opaque resources both return PERMISSION_DENIED. Race/event membership is additionally validated by the existing repositories. No business service accepts forwarded identity headers.

JWT access tokens are locally validated. Immediate revocation before expiration is not provided by introspection; use short-lived access tokens and provider-controlled issuance. Expired credentials must be renewed before replay.

## Durable replay and actor identity

Authorization runs on the decoded unary request before application callbacks. Every replay repeats the current event/player/POS checks. V5 command_actor binds newly authenticated operation/scope/key reservations to SHA-256(subject). A different subject cannot replay these acknowledgements even with identical request bytes. Same-subject replay preserves the exact original response bytes. Fingerprint conflicts still reject changed requests. Ownership checking and reservation run in the PostgreSQL transaction.

Existing V1–V4 records are not rewritten. Legacy commands without actor evidence remain replayable by callers authorized for the resource; no historical identity can safely be invented. This is an explicit migration limitation. A privileged legacy-record replay policy may need operational approval before migration.

## Profiles and transport

The default configuration requires external JWT configuration; it does not silently select insecure development. `dev` retains JWT authentication and loopback defaults. It can use a development issuer with HTTPS and trusted certificates. It has no bundled signing key. The test resource configuration activates `test`, excludes production security for legacy financial regression tests, and uses no HTTP listener. Test resources are not packaged in bootJar. The security suites select `security-test` explicitly and exercise actual JWT enforcement. Combining production and test/dev is rejected.

Production requires `SPRING_PROFILES_ACTIVE=production` and `TIKET_TRANSPORT=direct-tls` or `gateway-h2c`. Direct TLS requires `GRPC_TLS_ENABLED=true` and `SPRING_GRPC_SERVER_SSL_BUNDLE`. Use standard Spring SSL bundles, for example:

```properties
spring.ssl.bundle.pem.grpc.keystore.certificate=file:/run/secrets/server.crt
spring.ssl.bundle.pem.grpc.keystore.private-key=file:/run/secrets/server.key
spring.grpc.server.ssl.bundle=grpc
```

For mTLS add bundle trust material and `spring.grpc.server.ssl.client-auth=require`. Certificate validation must remain enabled. Certificates constrain transport peers; they do not grant application roles. JWT remains required. Rotate mounted certificates with a controlled process restart; verify hostname/SAN, chain and validity with clients before moving traffic.

Gateway mode sets GRPC_TLS_ENABLED=false. External TLS/HTTP2 terminates at the gateway; its connection to this listener is cleartext HTTP/2, not HTTPS. Restrict backend reachability to the gateway and trusted operators using platform networking. No Kubernetes APIs are called by the application.

## Management interface, health and logging

Minimal Spring MVC exists to host Actuator only. The application HTTP connector is disabled with server.port=-1. Management defaults to 127.0.0.1:9091. Only health and prometheus are exposed, and the HTTP security chain permits only the two probe paths and metrics. There are no REST business controllers. Env/configprops/dumps and business paths are denied. Metrics have no application credentials: management binding and deployment networking must protect them. Do not publish this port through a public ingress.

Liveness contains livenessState only. Readiness includes readinessState, native gRPC lifecycle state and database connectivity; migration failures abort startup. Database outages must not affect liveness. A dedicated native-listener health indicator checks GrpcServerLifecycle.isRunning and participates in readiness.

JSON Logstash-format logs go to stdout. Operational completion logs include a server-generated UUID correlation ID, bounded gRPC method, status and duration. Client correlation headers are ignored. No bearer metadata or request bodies are logged by the added code. Actor references are SHA-256 hashes of trusted subjects; they are pseudonyms, not anonymization against a known-subject dictionary. Existing console printer output contains printable ticket information and requires restricted log access.

## Metrics and tracing

Actuator supplies JVM/process/Hikari metrics and Prometheus formatting. The operational interceptor records tiket.grpc.requests and tiket.grpc.duration with only known method/status labels, plus overload counts. Idempotency replay/conflict counters are explicit. Existing gRPC observation remains enabled. Fixed-status print backlog gauges are cached by a scheduled query, with a separate availability gauge; print dispatch counters and attempt timers are recorded after transaction commit. IDs, subjects, request digests and idempotency keys are never metric labels.

The Boot OpenTelemetry starter supplies Micrometer Tracing and OTLP support. Export is disabled by default; set TRACING_EXPORT_ENABLED=true and OTEL_EXPORTER_OTLP_TRACES_ENDPOINT to an authorized collector. Collector availability is not a startup prerequisite. Do not add arbitrary baggage or financial payloads. Durable trace propagation into restarted print attempts is not yet implemented.

## Audit schema and trust

V5 adds operational_audit and command_actor; V1–V4 are untouched. Audit contains UUID identity, database timestamp, pseudonymous trusted actor, operation, resource scope, correlation UUID, request fingerprint/digest, outcome and failure category. It stores no token or complete financial body. Financial movement tables remain authoritative.

Authenticated command success and exact replay audit rows commit in the same transaction as durable command completion. Audit insert failure rolls back the command. Rolled-back attempts leave no successful audit. Command actor ownership is immutable. Statement triggers block UPDATE, DELETE and TRUNCATE on audit/actor tables, including zero-row operations. Database owners can bypass protections and must be separately controlled.

Network authentication/authorization failures use a separate REQUIRES_NEW audit transaction with a five-second timeout. Audit outages fall back to a safe error log with correlation and exception class, without changing denial to success. Financial success does not use this best-effort path. Administrative read evidence is independently recorded; decoded request selectors are recorded as canonical UUIDs and full request bytes are represented only by a SHA-256 digest. Authentication failures before message decoding necessarily have an undisclosed target. Rejected and failed RPC outcomes are independently recorded after rollback.

Audit deliberately has no resource foreign keys: denied attempts may reference nonexistent resources, and records must survive operational resource-retention decisions. command_actor has a composite foreign key to durable idempotency. Scope/time and actor/time indexes support incident investigation. Retention is an offline, separately privileged archival operation; application code never deletes history.

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

Remaining scope includes all-role positive RPC matrix tests, shutdown under sustained in-flight financial work, durable print trace propagation, full query/lock/CPU profiling of the large settlement and event-closure workloads, and stronger evidence for cluster-wide deployment behavior. JWT revocation before expiration requires an external policy. There is no claim of cross-host scalability. JWKS unknown-key/rotation and two-context recovery have dedicated Netty integration tests; mTLS has dedicated certificate/JWT separation tests. Passing the implemented suites does not satisfy every Phase 7 definition-of-done item.

## Phase 8 Deployment Contract

| Configuration | Contract |
| --- | --- |
| Java | JDK/JRE 25; existing Boot/Kotlin/Gradle versions retained |
| SPRING_PROFILES_ACTIVE | production; never test/dev on deployed financial services |
| TIKET_TRANSPORT | direct-tls or gateway-h2c, mandatory in production |
| GRPC_ADDRESS / GRPC_PORT | 127.0.0.1 / 9090 defaults; explicitly bind the private service interface |
| GRPC_TLS_ENABLED | true for direct TLS, false for gateway h2c |
| SPRING_GRPC_SERVER_SSL_BUNDLE | Required direct-TLS bundle name |
| JWT_ISSUER / JWT_JWKS_URI / JWT_AUDIENCE | Mandatory trusted external HTTPS issuer/JWKS and access-token audience |
| TIKET_ENVIRONMENT | Static environment field in structured JSON logs; unspecified default |
| JWT claims | Exact roles, event/player/POS mandates and cashier mapping above |
| MANAGEMENT_ADDRESS / MANAGEMENT_PORT | 127.0.0.1 / 9091 defaults; restricted network only |
| Probe paths | /actuator/health/liveness and /actuator/health/readiness |
| Metrics | /actuator/prometheus; protect through binding/network policy |
| HTTP application port | -1; no business HTTP listener |
| DB_URL / DB_USERNAME / DB_PASSWORD | Ordinary PostgreSQL JDBC, externally supplied secrets and verify-full TLS |
| DB_POOL_SIZE / DB_CONNECTION_TIMEOUT | 10 / 5000ms defaults; size across all replicas |
| GRPC_MAX_MESSAGE | 1MB default |
| tiket.limits.concurrent | 32 active calls per process |
| tiket.limits.requests-per-second | 60 per subject per process |
| tiket.database.transaction-timeout-seconds | 20 default |
| TRACING_EXPORT_ENABLED | false default; enable with configured OTLP collector |
| OTEL_EXPORTER_OTLP_TRACES_ENDPOINT | Optional collector traces URL |
| Printing | Existing tiket.printing.* lease/retry/worker properties; at-least-once delivery |
| Shutdown | 25s gRPC/scheduling grace; platform budget must exceed lifecycle shutdown |

Phase 8 must supply network isolation, secret/certificate mounts, migration/runtime roles, ingress TLS/HTTP2 behavior, optional cluster-wide rate limiting, resource sizing and monitoring. No Dockerfiles, orchestration manifests, CI workflows or release automation are added here.

References: [Spring gRPC security](https://docs.spring.io/spring-grpc/reference/server.html), [Boot native gRPC SSL](https://docs.spring.io/spring-boot/reference/io/grpc.html), [Boot tracing](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html).

## Change inventory and dependencies

New Boot-managed dependencies: security (official security integration), OAuth2 Resource Server (JWT/JWKS), Actuator (health and metrics), Web MVC (management HTTP only), Prometheus registry (scraping) and OpenTelemetry starter (optional Micrometer/OTLP tracing). No existing version is upgraded.

Changed files:

- `README.md`
- `docs/backend-architecture.md`
- `docs/backend-persistence.md`
- `docs/phase7-security-operations.md`
- `tiket-backend/build.gradle.kts`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/application/PrintOutbox.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/grpc/AdministrationGrpcServices.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/persistence/IdempotencyRepository.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/persistence/JdbcSupport.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/AuditIdentity.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/GrpcReadiness.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/OperationalInterceptor.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/OutboxMetrics.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/ProductionGuard.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/ProfileGuard.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/ResourceAuthorization.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/RpcPolicy.kt`
- `tiket-backend/src/main/kotlin/pl/umamusume/tiket/backend/security/SecurityConfiguration.kt`
- `tiket-backend/src/main/resources/application-production.yaml`
- `tiket-backend/src/main/resources/application.yaml`
- `tiket-backend/src/main/resources/db/migration/V5__security_audit.sql`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/AuditPersistenceTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/FinancialSecurityTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/JwksRotationTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/MtlsTransportTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/MultiInstanceSecurityTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/PersistenceTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/PostgresFoundationTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/ProductionConfigurationTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/SecurityTlsTransportTests.kt`
- `tiket-backend/src/test/kotlin/pl/umamusume/tiket/backend/SecurityTransportTests.kt`
- `tiket-backend/src/test/resources/application.properties`

## Executed validation

The clean build with configuration/build caches disabled completed successfully in 3m 54s on this host. JDK/Gradle native-access warnings were non-fatal. An earlier Gradle daemon hit the default 384 MiB metaspace ceiling; validation used a command-local override of 768 MiB heap and metaspace plus two active processors. Application resource defaults were not raised.

| Check | Result |
| --- | --- |
| clean build --no-configuration-cache --no-build-cache | All five modules successful |
| :tiket-core:test --no-configuration-cache | Exit 0; 34 core tests passed during the clean run |
| :tiket-contracts:build --no-configuration-cache | Exit 0 |
| :tiket-backend:test --no-configuration-cache | Exit 0; 215 tests, 0 failures/errors/skips in the full run |
| :tiket-backend:bootJar --no-configuration-cache | Exit 0 |

All separate checks were executed after the clean run; up-to-date tasks reused that run's results. PostgreSQL 18 Testcontainers actually ran; Docker was available. No tests were disabled to make the build green.

Backend suite counts: AdministrationTests=8, AuditPersistenceTests=1, BettingPrintingTests=35, FinancialSecurityTests=2, JwksRotationTests=1, MtlsTransportTests=2, MultiInstanceSecurityTests=1, NettyGrpcTests=1, PersistenceTests=19, Phase5Tests=20, Phase6Tests=15, PostgresFoundationTests=1, ProductionConfigurationTests=7, SecurityTlsTransportTests=50, SecurityTransportTests=50, TiketBackendApplicationTests=2.

Build logs are local review artifacts under build/build-phase7-complete.log and build/phase7-observability-validation.log. The full regression run was repeated after JSON service/environment logging configuration. A final targeted regression additionally checks structured RPC/correlation fields and bearer-token absence, unauthorized purchase POS/cashier/player/event selectors, changed fingerprints and different-actor replay.

The remaining validation/profile gaps above are deviations from the requested full Phase 7 definition of done. This increment must not be represented as completing every production-readiness requirement, and Phase 8 was not started.

Final targeted validation: FinancialSecurityTests (2) and MultiInstanceSecurityTests (1) passed after the structured-log field/redaction and expanded cashier/POS/player/event replay assertions; bootJar succeeded in the same invocation. Captured operational records contain traceId, spanId, request_id, grpc_method, classification, service and environment fields. IntelliJ build_project also reported isSuccess=true with no build problems. git diff --check exited 0. Detailed IDE SQL/schema inspections may require refreshing the local V5 datasource model; database migration and runtime tests are authoritative for schema availability.

After the IDE build, dependency references resolved. Two remaining IntelliJ inspections are model limitations: the unconfigured local operational_audit schema and a conditional auto-configured MeterRegistry bean. Actual PostgreSQL migration, Spring context injection, native transport and IDE/Gradle builds passed.
