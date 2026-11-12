# Local development
Use JDK 25, the checked-in Gradle 9.7 wrapper, PostgreSQL 18 and Docker for Testcontainers. Kotlin 2.4.21 and Spring Boot 4.1.2 are pinned by the build; do not install alternate Gradle globally.

From PowerShell:
```powershell
.\gradlew.bat build --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m"
docker run --rm --name tiket-db -p 127.0.0.1:5432:5432 --env-file local-db.env postgres:18-alpine
```

Create the untracked `local-db.env` with `POSTGRES_DB=tiket`, `POSTGRES_USER=tiket` and a locally chosen `POSTGRES_PASSWORD`. Keep the database terminal open. In another PowerShell session:
```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/tiket?connectTimeout=5&socketTimeout=30'
$env:DB_USERNAME = 'tiket'
$env:DB_PASSWORD = '<local database password>'
$env:JWT_ISSUER = 'https://identity.example/issuer'
$env:JWT_JWKS_URI = 'https://identity.example/jwks'
$env:JWT_AUDIENCE = 'tiket'
.\gradlew.bat :tiket-backend:bootRun --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```
Replace identity URLs with your reachable provider. A valid RS256 JWT and resource grants are required for business calls, including locally. Flyway runs V1–V5; gRPC listens on loopback 9090 and management on loopback 9091. No GUI opens. Reflection is disabled. Console printing is the default.

On Bash use `./gradlew` and `export NAME='value'` instead of PowerShell's environment syntax. The Gradle options are the same. See [authentication](../api/authentication.md) before exposing a listener. The disposable database above loses data when removed; use managed durable storage for real events.
