# Phase 1 validation

Validated locally on 2026-11-10 using Windows 11, Java 25, Gradle wrapper 9.7.0,
Kotlin 2.4.21 and Spring Boot 4.1.2.

Successful Gradle invocations (Windows wrapper):

```text
gradlew.bat clean build --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
gradlew.bat :tiket-contracts:build :tiket-backend:test :tiket-backend:bootJar --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```

Protobuf and Java gRPC generation, all module compilation, core consumer compatibility,
root build and executable backend packaging succeeded. The final clean build reused
successful task outputs from Gradle's build cache.

Four backend tests actually executed and passed with zero skips:
official in-process Ping, real loopback Netty Ping, core public API consumption,
and PostgreSQL foundation integration. PostgreSQL 18.6 ran in Testcontainers using
the postgres:18-alpine image. The full application started twice against that database,
ran/validated V1, performed no additional migrations on repeat, returned OK/v1 over
generated Java gRPC client stubs and shut down the listener and Hikari pool.
The context was non-web and had no HTTP server port.

Initial attempts encountered Windows commit-memory exhaustion starting test JVMs
and a Kotlin daemon loopback socket bind failure. Smaller test JVM heaps and
in-process compilation resolved validation locally. Docker was initially unavailable
but became available before integration tests ran. No PostgreSQL integration test was skipped.

Known build limitation: Protobuf generation tasks do not support the configuration cache;
they are marked accordingly. Standard Gradle/JDK native-access and Mockito agent warnings
do not fail the build. No Phase 2 functionality was introduced.
