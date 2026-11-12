# JVM and containers

## Build and ordinary JVM execution

Keep JDK 25, Kotlin 2.4.21, Boot 4.1.2 and wrapper 9.7.0.
Run `./gradlew clean build --no-configuration-cache --no-build-cache` (Windows:
`gradlew.bat`). Docker must be available for the PostgreSQL 18 Testcontainers suites.
Use `-PreleaseVersion=1.2.3` to set all module artifact versions. The executable
is `tiket-backend/build/libs/tiket-backend-<version>-boot.jar`; other JARs are libraries.
On Windows, quote `"-Pkotlin.compiler.execution.strategy=in-process"` and use one
worker if necessary. `"-Pkotlin.incremental=false"` avoids corrupted incremental
classpath snapshots after interrupted compilations.

VM deployments use the same executable: `java -jar tiket-backend-<version>-boot.jar`.
Supply DB_URL, DB_USERNAME, DB_PASSWORD, SPRING_PROFILES_ACTIVE=production,
TIKET_TRANSPORT, GRPC_ADDRESS, MANAGEMENT_ADDRESS and JWT_ISSUER/JWT_JWKS_URI/JWT_AUDIENCE.
The issuer and JWKS URLs must be HTTPS; configure the JVM trust store for a private CA.
There are no runtime Kubernetes API dependencies. Keep management on loopback for VMs.
See [authentication](../api/authentication.md) for the complete claim model and SSL bundle properties.

## OCI image

Build the bootJar first, then `docker build -t tiket:kind .`.
The Dockerfile pins the verified Red Hat base
`registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24@sha256:2ddacad5332f4e481353668f210623f7e7420f1561515f5686e5abd33cd6d38f`.
Its registry manifest lists linux/amd64, arm64, ppc64le and s390x.
Only amd64 has been executed locally. Manifest availability is not application parity.

The default numeric UID is 185; arbitrary nonzero UIDs need no passwd entry.
The JAR is world-readable and immutable. Java is PID 1 through an exec-form entrypoint.
Tiket adds no build tools, embedded credentials or TLS material to the runtime base.
JAVA_TOOL_OPTIONS directs temporary files and user home to /tmp. When overriding it,
retain `-Djava.io.tmpdir=/tmp -Duser.home=/tmp`.
Use a tmpfs /tmp with a read-only root, drop all capabilities, prohibit privilege
escalation, and pass secrets through an approved runtime mechanism.

Example Java-only smoke:
`docker run --rm --read-only --user 1001234567:0 --tmpfs /tmp --entrypoint java tiket:kind -version`.
For application startup, pass the production environment and external DB/JWT settings,
map only the private gRPC port, and provide TLS secrets as required. `docker stop --time 90`
allows the JVM shutdown hook to complete. Do not expose management to the public.
Podman accepts the same Dockerfile and ordinary run settings; Podman was unavailable locally.

Podman command compatibility is expected but has not been tested. GraalVM native executables are not supported by this delivery path.

For an actual private container process, prepare an untracked `runtime.env` with the database, JWT and production transport settings described above, and mount the required TLS/CA files read-only. An example command (replace the immutable digest) is:

```sh
docker run --rm --name tiket --read-only --user 185:0 --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp --env-file runtime.env -v /srv/tiket/secrets:/run/secrets:ro -p 127.0.0.1:9090:9090 ghcr.io/wiesmak/tiket@sha256:<verified-image-digest>
```

Set GRPC_ADDRESS=0.0.0.0 inside the container; retain loopback host publication above. Configure direct TLS bundles or keep h2c strictly private behind a trusted TLS terminator. Do not publish management port 9091. Migrate first using the controlled migration identity, then set SPRING_FLYWAY_ENABLED=false for runtime credentials. On a VM, run the same boot JAR under a dedicated service account and supervisor with restricted secret files, durable logs and a graceful stop timeout.
