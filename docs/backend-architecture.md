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

Flyway owns schema evolution. V1 establishes migration history only. Business schema,
repositories, idempotency and concurrency policies require Phase 2 design.
Printing will eventually use a separate printer contract and adapter; neither exists yet.

The default process uses no web application context and binds plaintext gRPC to
127.0.0.1:9090. PostgreSQL credentials are supplied externally. Production TLS and
authentication/authorization remain required future work before remote access.

Build conventions retain a single Kotlin version and Java 25 toolchain. The Spring Boot
plugin applies only to backend. Contracts explicitly configures its own protoc and gRPC
generator, and uses the Boot BOM for compatible message/stub dependencies. Core's
dependencies and calculations are unchanged.
