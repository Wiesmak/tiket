# Building, testing and contributing
Use Java 25 and the checked-in wrapper. Docker must be running for PostgreSQL 18 Testcontainers. Do not run serialSmoke as part of automated validation.

Windows PowerShell:
```powershell
.\gradlew.bat build :dokkaGenerate --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m"
```
Linux CI:
```sh
bash scripts/ci-gradle.sh clean build --no-build-cache
./gradlew :dokkaGenerate --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
npm ci --prefix scripts
node scripts/validate-helm.mjs
node --test scripts/security/*.test.mjs
```
The CI wrapper asserts 1536 MiB heap, 1024 MiB metaspace and one worker, with in-process Kotlin. Protobuf disables configuration cache. Preserve these limits when diagnosing a constrained runner; native memory, test JVMs and Docker need additional capacity.

Core check includes an independent consumer-compatibility compilation without friend access. Backend tests exercise real PostgreSQL migrations, rollback, concurrency, financial proofs, JWT/TLS and print-outbox recovery. Adapter tests use streams and mocked ports; no hardware is needed.

For isolated cluster testing use `bash scripts/test-kind.sh` or `powershell -File scripts/test-kind.ps1`. It creates and deletes only its own random kind cluster and temporary kubeconfig. This tests ordinary PostgreSQL and disposable Gateway traffic, not CNPG failover or real OpenShift admission. Use `--ingress` for the separate Ingress scenario. Never target a persistent cluster with test fixtures.

Submit focused changes with relevant tests and updated docs. Keep core pure, preserve wire contracts and applied migrations, and record behavior defects separately from documentation changes. Never include credentials, raw crash dumps or physical receipt payloads in public evidence.
