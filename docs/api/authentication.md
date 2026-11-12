# Authentication and permissions

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
