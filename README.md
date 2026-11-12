# Tiket

Tiket is a Kotlin/JVM parimutuel horse-betting system: a reusable domain library and a gRPC backend for administering race days, accepting coupons, settling pools and recording final accounts.

Each race has four independent pools: **WIN**, **QUINELLA**, **EXACTA** and **TRIFECTA**. Ordinary, positional wall (`*`) and box (`#`) selections expand into canonical atomic legs. Stakes, dividends, refunds and promotional eligibility use checked integer arithmetic. For example, Exacta `#2,3,4` at 2 units per combination and unit stake 10 creates 6 combinations, 12 units and total stake 120.

There is no graphical cashier application, external payment transfer or automatic cash dispenser. A recorded withdrawal does not prove money was delivered.

## Modules

| Module | Purpose |
| --- | --- |
| tiket-core | Immutable domain and pure betting/financial policies |
| tiket-contracts | Protobuf definitions and generated Java gRPC stubs |
| tiket-backend | Spring Boot application, JDBC transactions, JWT security and outbox |
| tiket-print-api | Immutable receipt contract and printer extension point |
| tiket-print-console | Development/stdout dispatch |
| tiket-print-serial | Local 384-pixel raster serial printing |

The build uses Kotlin 2.4.21, Java 25, Gradle 9.7, Spring Boot 4.1.2, gRPC/Protobuf, PostgreSQL 18 and Flyway. Deployment uses a JVM OCI image and one portable Helm chart.

## Quick start

Install JDK 25 and Docker with a Linux engine. From PowerShell:

```powershell
.\gradlew.bat build --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m"
```

Docker is required for PostgreSQL integration tests. On Bash use `./gradlew` with the same options.

To run locally, provision PostgreSQL 18 and a reachable HTTPS JWT issuer/JWKS provider. Supply these environment variables in PowerShell:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/tiket?connectTimeout=5&socketTimeout=30'
$env:DB_USERNAME = '<database user>'
$env:DB_PASSWORD = '<database password>'
$env:JWT_ISSUER = 'https://identity.example/issuer'
$env:JWT_JWKS_URI = 'https://identity.example/jwks'
$env:JWT_AUDIENCE = 'tiket'
.\gradlew.bat :tiket-backend:bootRun --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```

Replace placeholders with your local configuration. Flyway initializes/validates the schema. gRPC defaults to `127.0.0.1:9090`; management defaults to `127.0.0.1:9091`. Business calls require valid JWT claims and resource grants. See the [local setup](docs/getting-started/local-development.md) for a disposable database command.

## API and user guides

The contracts live in [tiket-contracts/src/main/proto](tiket-contracts/src/main/proto/pl/umamusume/tiket/api/v1). Reflection is disabled; clients should load these files.

- [gRPC overview](docs/api/overview.md), [generated schema](docs/api/grpc-reference.md), [worked client flow](docs/api/examples.md)
- [Permissions and authentication](docs/api/authentication.md)
- [Betting](docs/concepts/betting.md), [financial model](docs/concepts/money-and-settlement.md), [settlement](docs/guides/settlement.md), [cashout](docs/guides/cashout.md)
- [Architecture](docs/concepts/architecture.md) and [Kotlin API generation](docs/library-api.md)

## Printing and deployment

Purchases commit a durable print job in the same database transaction. Console printing writes receipt data to stdout. The interchangeable serial adapter uses escpos-coffee/jSerialComm and local COM/Linux devices. Dispatch is at least once; stream success does not acknowledge physical paper. See [printing](docs/guides/printing.md) before connecting hardware.

Build the executable with `:tiket-backend:bootJar`, then `docker build -t tiket:local .`. Follow [JVM/container deployment](docs/deployment/jvm-and-containers.md), [Helm/Kubernetes/OpenShift](docs/deployment/helm.md), [database migration](docs/deployment/database.md) and [signature verification](docs/deployment/release-verification.md).

Snapshots are **DEVELOPMENT SNAPSHOT — NOT PRODUCTION APPROVED**. Development gates warn on HIGH findings; CRITICAL findings and scanner errors block. Tagged releases block HIGH and CRITICAL. Image and chart signing/verification remain mandatory.

Production requires JWT resource scoping, protected TLS or trusted private h2c, isolated management, external secrets and least-privilege database credentials. Exceptional results/reversals, actual money disbursement, sustained-load scaling, CNPG failover, real OpenShift admission and consecutive physical printing remain limitations or unverified areas. See [operations and recovery](docs/operations/recovery.md). There is no native GraalVM support claim.

## Documentation

The source-controlled site uses MkDocs 1.6.1 with its built-in readthedocs theme. Python 3.13 dependencies are pinned with hashes.

```sh
python -m venv .venv-docs
# Activate: PowerShell .venv-docs\Scripts\Activate.ps1
# Activate: Bash source .venv-docs/bin/activate
python -m pip install --require-hashes -r requirements-docs.txt
python scripts/docs.py install-tools
# Windows: replace ./gradlew with .\gradlew.bat
./gradlew :tiket-contracts:generateProto :dokkaGenerate --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
python scripts/docs.py proto --check
python scripts/validate-docs.py
mkdocs build --strict
python scripts/docs.py assemble
python -m http.server --directory site 8000
```

Browse `http://localhost:8000`. Use `mkdocs serve` for live Markdown preview. See [documentation development](docs/development/documentation.md) for regeneration. Output is static, searchable and needs no application server or database. No automatic public deployment is enabled.

## License

Project source and documentation are licensed under [Apache License 2.0](LICENSE), SPDX `Apache-2.0`. Third-party components retain their own rights and notices; the project license grants no third-party trademark rights.
