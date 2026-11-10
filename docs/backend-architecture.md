# Backend architecture

Phase 1 contains a single generated contract and a single gRPC adapter:
`SystemGrpcService`, registered as a Spring gRPC service. Spring Boot owns the Netty
server lifecycle. Ping has no domain or database side effects.

The intended dependency direction for later phases is:

```text
gRPC adapter -> application service -> core domain
                                  -> persistence adapter -> PostgreSQL
```

The backend depends on contracts and core as ordinary Gradle projects. Contracts contains
only Protobuf definitions and generated Java messages/service stubs. Core does not depend
on contracts, Spring, JDBC or transport. A backend test compiles against core's public
Money API without changing domain code.

Future gRPC adapters will translate transport messages into application inputs. Application
services will orchestrate core decisions and transaction boundaries; SQL adapters will
persist resulting state through Spring JDBC. Protobuf objects must never become core domain
models. Package names will be `grpc`, `application`, `persistence` and `config` when
classes are needed; no future implementations are scaffolded now.

Flyway owns schema evolution. V1 establishes migration history only. Phase 2 adds the business schema,
JDBC repositories, idempotency and concurrency primitives; see [persistence](backend-persistence.md).
Phase 4 printing uses separate print-api and print-console libraries, wired by backend configuration.

The default process uses no web application context and binds plaintext gRPC to
127.0.0.1:9090. PostgreSQL credentials are supplied externally. Production TLS and
authentication/authorization remain required future work before remote access.

Build conventions retain a single Kotlin version and Java 25 toolchain. The Spring Boot
plugin applies only to backend. Contracts explicitly configures its own protoc and gRPC
generator, and uses the Boot BOM for compatible message/stub dependencies. Core's
dependencies and calculations are unchanged.

## Phase 3 implementation

The gRPC -> application -> core/persistence direction is now implemented for administration and enrollment.
RaceDayApplicationService, RaceApplicationService and EnrollmentApplicationService orchestrate policies,
locks, expected versions and transactions. AdministrationCommands stores exact Protobuf acknowledgements
in durable idempotency references. Three thin generated-service adapters map sanitized errors.
See [Phase 3](phase3-administration.md) for the RPC inventory and security boundary.

## Phase 4 implementation

BettingApplicationService orchestrates core quote/purchase policies and JDBC repositories.
BettingGrpcService exposes structured selections and immutable coupon queries.
PrintOutbox owns durable jobs/attempts and reprint idempotency; PrintingGrpcService provides status.
PrintWorker claims jobs transactionally, dispatches outside transactions and records outcomes.
Backend resolves TicketPrintData; print-api has no core/framework dependencies and print-console
only depends on print-api. See [Phase 4](phase4-betting-printing.md) for contracts and recovery semantics.

## Phase 5 implementation

RaceFinancialApplicationService coordinates RefundPlanner, ResultValidator, RaceLifecyclePolicy and
RaceSettlementPlanner with the existing JDBC repositories and AdministrationCommands. RaceService
adds scratch/void; ResultGrpcService exposes proposals, verification, settlement and bounded audit queries.
Four pools settle in one transaction; grouped account credits append once per player. Verification
writes race state before changing result status, then reconstructs final state before commit.
The only core behavior correction clears a selected proposal on void. No framework or version changes,
new wallet, printing changes or Phase 6 commands are introduced. See [Phase 5](phase5-refunds-settlement.md).

## Phase 6 final accounting

AccountApplicationService coordinates validated ledger reads, core withdrawal quotes and
atomic two-movement cashout. FinalAccountingProof establishes terminal race/pool/leg evidence
for completion and replays all enrolled accounts for closure. AccountGrpcService exposes the
new typed v1 account API. RaceDayApplicationService retains day locks through proof and update.
V1–V4 are reused without a new migration; cashout increments the account version once.
See [Phase 6](phase6-accounting-closure.md) for proofs, transactions, receipts, performance
tradeoffs and the boundary between registered cashout and actual disbursement.
