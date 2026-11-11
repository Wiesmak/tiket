# Phase 8 deployment and delivery

This increment provides a JVM image, one portable Helm chart and ephemeral kind automation.
It is not a production-readiness certification. Validation evidence and remaining gates
are recorded below. Phase 9 has not been implemented.

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
See Phase 7 for the complete claim model and SSL bundle properties.

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

## Local and CI kind suite

Prerequisites: Java 25, the built bootJar, Node 22.18+, npm, Docker Linux engine,
kind 0.33.0, kubectl compatible with Kubernetes 1.35 and Helm 4.1.3.
No permanent Kubernetes cluster is needed. Docker Desktop supplies only the Docker engine.
Internet access is needed for pinned test images, npm packages and disposable controllers.

Full local command:
- Unix/macOS: `bash scripts/test-kind.sh`
- Windows: `powershell -File scripts/test-kind.ps1`

Both wrappers install the locked Node test dependencies and run `scripts/test-kind.mjs`.
Keep local ports 19080 and 19090-19095 free for isolated port forwarding and Docker checks.
CI invokes that same runner. It creates a random cluster name and isolated temporary
kubeconfig; every kubectl/Helm operation explicitly selects that file. The default user
context is never used. The runner builds/loads Tiket, deploys fixtures, installs the real
production chart, runs traffic checks, records diagnostics under build/kind-*, then removes
only its own cluster. A finally block and signal handlers perform cleanup; cleanup retries
transient Docker failures and a failure to clean up fails the run. Commands have bounded
timeouts. GitHub Actions also deletes the emitted cluster name under `if: always()`.
A forced OS/process kill cannot execute language-level finally; CI's final cleanup is a
second guard. Never delete unrelated clusters.

Kubernetes 1.35 rejects cgroup v1 by default. Prefer a cgroup v2 Docker engine. On an older
local Docker Desktop engine, explicitly append `--allow-cgroup-v1` to enable the temporary
kind kubelet's failCgroupV1=false setting. This changes only the disposable node, not the
host or production chart. CI uses the normal cgroup v2 path.
`--no-gateway` skips controller traffic for constrained environments and is not equivalent
to full validation. `--ingress` selects a separate disposable Traefik scenario instead of
Envoy Gateway. It never renders both public exposure resources.

The fixture uses standard postgres:18.1, generated credentials, Service DNS, a temporary
PVC inside the kind node, and separate migrator/runtime roles. Cluster deletion removes
the storage. PostgreSQL is not a production chart dependency. The HTTPS JWKS fixture uses
one-run RSA signing material, certificates and scoped JWTs; no real identity credentials
are used. Its CA is imported into a temporary Java trust store. TLS validation stays on.

The runner checks probes/metrics, missing/expired/wrong-issuer/wrong-audience/tampered JWTs,
role/event/POS restrictions, same/different-actor replay, concurrent duplicate purchase,
restart persistence, two replicas, pending print dispatch after replacement, concurrent
cashout replay, PostgreSQL outage/recovery and direct TLS. Full financial regression stays
in Gradle/Testcontainers. Pending-job restart is not proof of interrupted physical printing
or sustained-load shutdown. Inspect executed results before claiming any scenario passed.

The default controller is disposable Envoy Gateway v1.8.6, installed with its Gateway API
CRDs. A test Gateway and the actual chart's v1 GRPCRoute carry native gRPC over HTTP/2.
The suite checks authorized/denied requests and that an Actuator HTTP path is not routed.
No public production Gateway is created by the chart. Optional Ingress uses a pinned
Traefik chart; its result must be recorded separately. The disposable controller listeners
test cleartext HTTP/2 forwarding. Production frontend TLS listeners are externally
provisioned and must be validated with the selected controller; backend TLS is tested separately.

## One Helm chart

`charts/tiket` contains Deployment, two ClusterIP Services, ServiceAccount, ConfigMap,
migration Job, optional PDB/HPA, NetworkPolicy, ServiceMonitor, GRPCRoute and Ingress.
No PostgreSQL, CNPG, Keycloak, Route, ImageStream or BuildConfig is created.
The chart does not install operators. HPA requires metrics-server and realistic CPU
requests; PDB requires multiple replicas for meaningful voluntary disruption tolerance.

Render/lint/schema matrix: `npm ci --prefix scripts && node scripts/validate-helm.mjs`.
It asserts private management, restricted runtime settings, exclusive exposure and
absence of prohibited resources. Rendering alone does not validate Gateway traffic.

Default exposure.mode=none is private. Configure trusted JWT URLs and external Secrets
before installation. Defaults intentionally cannot start against a real identity provider.
Install source chart using `helm upgrade --install tiket charts/tiket -f production.yaml --wait --timeout 10m`
with an explicitly selected kubeconfig/context. Once published, use
`helm install tiket oci://ghcr.io/wiesmak/charts/tiket --version X.Y.Z -f production.yaml`.
Image tag defaults to chart appVersion; image.digest overrides the tag.
Pin the desired release digest in production values.

Gateway example:
```yaml
exposure:
  mode: gateway
  gateway:
    parentRefs:
      - name: existing-gateway
        namespace: infrastructure
        sectionName: grpc-tls
    hostnames: [tiket.example.org]
transport: gateway-h2c
jwt:
  issuer: https://id.example.org/realms/tiket
  jwksUri: https://id.example.org/realms/tiket/protocol/openid-connect/certs
  audience: tiket
```
The Gateway listener must allow this namespace/route and terminate external TLS with HTTP/2.
The internal Service advertises kubernetes.io/h2c. JWT is mandatory on that hop.
Identity is never inferred from forwarded headers. Restrict private backend reachability.

Ingress mode requires hostname, className, optional tlsSecret and controller annotations.
For ingress-nginx the backend-protocol annotation is GRPC for h2c or GRPCS for backend TLS.
For Traefik use the gRPC-aware h2c service annotation through service.annotations.
Controller support for native gRPC and HTTP/2 is mandatory. Direct TLS through Gateway
or Ingress may require a controller-specific backend TLS policy, CA and hostname settings;
the chart does not invent a portable BackendTLSPolicy for every controller.
For direct TLS set transport=direct-tls and tls.secret to a Secret containing tls.crt/tls.key.
mTLS additionally needs ca.crt and tls.clientAuth=require (optional requests a certificate).
JWT remains required for mTLS clients.

Pods use runAsNonRoot without a fixed runAsUser, RuntimeDefault seccomp, no privilege
escalation, ALL capabilities dropped, read-only root and an emptyDir /tmp.
No service-account token, hostPath, host networking or privileged SCC exception is required.
OpenShift assigns its allowed numeric UID. Actual OpenShift admission/SCC behavior is untested.
The 90s termination grace exceeds the application's 25s gRPC and 30s lifecycle phase budgets.
Size aggregate connection pools across replicas; event-wide financial locks constrain scaling.
There is no leader election or unlimited horizontal-scalability claim.

## External PostgreSQL / CNPG and migration control

CNPG is externally provisioned. Set database.url to an ordinary JDBC URL, normally using
<cluster>-rw:5432/tiket. Include connectTimeout/socketTimeout and production
sslmode=verify-full. database.tlsSecret mounts the CA and optional client certificate/key
at /run/db. Specify sslrootcert, sslcert and sslkey paths explicitly in the JDBC URL.
PostgreSQL JDBC client-key format must match the driver's requirements (PKCS#8/PKCS#12);
a Kubernetes TLS Secret's arbitrary PEM key is not automatically converted.
verify-full requires certificate SANs matching the Service DNS name.

database.runtimeSecret and migrationSecret each reference an externally created Secret
with username/password keys. No password is rendered into ConfigMaps. Limit Secret
read access, protect mounted keys and use controlled restarts for rotation.
Configure DB_POOL_SIZE/DB_CONNECTION_TIMEOUT through database values.

The migrator owns the database/schema and Flyway objects. The runtime role gets schema
USAGE and needed SELECT/INSERT/UPDATE/DELETE on ordinary tables plus sequence permissions,
but no CREATE/schema ownership. Restrict operational_audit/command_actor to SELECT/INSERT.
Set default privileges as migrator before migration, then revoke mutation privileges on
the audit tables after migration. Historical V1-V5 checksums remain unchanged.
Do not use a superuser in production.

The executable argument `--tiket.migrate-only` runs Flyway directly without a Spring context,
gRPC, management or workers. It requires the migration DB environment and validates checksums.
Normal chart replicas set SPRING_FLYWAY_ENABLED=false and use only runtime credentials.
With migration.mode=hook, one pre-install/pre-upgrade Job gates Helm's Deployment update.
A failed Job aborts that Helm operation; old application replicas can continue serving.
Do not deploy new-schema code by bypassing the migration gate.

For GitOps use migration.mode=explicit, a unique migration.revision for the cycle, and
an orchestrator dependency/sync wave that waits for Job completion before applying the
Deployment. Rendering an ordinary Job alone does not gate a Deployment. After executing
that Job, disable migration.enabled in the rollout phase to avoid a second migration path.
Choose either explicit or Helm-hook orchestration for each deployment cycle.
No automatic database rollback is promised. Back up before upgrades and test restoration.

An explicit ordered deployment can use the following equivalent steps (supply the
intended kubeconfig and reviewed production values; stop on any nonzero exit):

```sh
set -euo pipefail
helm template tiket charts/tiket -f production.yaml \
  --set migration.mode=explicit --set migration.revision=upgrade-20261111 \
  --show-only templates/migration.yaml > migration.yaml
migration_job=$(kubectl --kubeconfig "$TIKET_KUBECONFIG" apply -f migration.yaml -o name)
kubectl --kubeconfig "$TIKET_KUBECONFIG" wait --for=condition=complete "$migration_job" --timeout=5m
helm upgrade --install tiket charts/tiket -f production.yaml \
  --set migration.enabled=false --kubeconfig "$TIKET_KUBECONFIG" --wait --timeout=10m
```

Use a new revision for each GitOps migration cycle and retain Job evidence. A failed
wait must prevent the final command. The normal Helm-hook path does not run these
explicit steps.

CNPG operator failover, certificate rotation, reconciliation and storage HA are unverified.
A standard PostgreSQL restart/outage test is not CNPG operator validation.

## Management and NetworkPolicy

Kubelet probes reach management on 0.0.0.0 inside the pod. The management Service is
ClusterIP only and is never a public backend. Liveness excludes PostgreSQL; readiness
includes DB and native gRPC. Metrics are /actuator/prometheus; optional ServiceMonitor
requires the Prometheus Operator CRD. Business HTTP remains disabled.

Enable NetworkPolicy and configure grpcPeers/monitoringPeers with both namespace and
pod selectors for the trusted controller, approved internal clients and monitoring.
Egress permits selected kube-dns, configured database peers and explicit externalEgress
rules. Configure dnsPeers/dnsPorts for the actual cluster DNS namespace, pod labels
and target ports; defaults describe upstream CoreDNS, not OpenShift DNS. Set database.port
to match the JDBC URL when using a nondefault PostgreSQL port.
Include the migration-job networking in any namespace-wide deny policy.
The chart's policy selects application pods only; infrastructure remains externally governed.
Allow PostgreSQL on its actual port and HTTPS JWKS destinations; optional OTLP needs its
own rule. Portable NetworkPolicy has no DNS-name egress selector. Use explicit CIDRs,
an egress proxy or CNI-specific policy outside this chart where necessary.
Do not claim enforcement with a CNI that does not implement policy (including a default
kind setup). No unrestricted public application ingress is enabled by this policy.

## CI, snapshots and signed releases

PR CI uses GitHub-hosted Ubuntu/Docker, wrapper validation, Java 25, full Gradle tests,
image build, Helm matrix, random-UID smoke and the same real kind/Gateway runner.
It uses no release credentials or production kubeconfig. Actions are pinned by commit.

Create protected GitHub environments snapshot and release. Restrict snapshot to trusted
master; restrict release to protected semantic version tags, required reviewers, and
repository rules preventing tag movement. Workflow regex validation is not a substitute
for owner-configured tag protection. Artifacts are published, not deployed.
Both publication entrypoints depend on the reusable validation workflow.

Snapshots use 0.0.0-dev.<run>.<attempt>.g<shortsha>; vX.Y.Z tags map to X.Y.Z across Gradle, image,
chart version/appVersion and release assets. Leading-zero/nonsemantic tags fail.
Publishing checks that image/chart versions do not already exist; serialize trusted
publishers and prohibit manual overwrite. GitHub's OCI registry does not itself provide
a transactional multi-artifact publish. A failed signing run can leave a published digest
without a completed release: investigate and record it; never call it verified.

Protected secrets:
- TIKET_COSIGN_PRIVATE_KEY: encrypted native Cosign private-key file contents.
- TIKET_COSIGN_PASSWORD: its nonempty passphrase.
- TIKET_SIGNING_CERT and TIKET_SIGNING_CERT_CHAIN: optional matching PEM certificate/chain.

A certificate cannot sign. Convert an existing supported unencrypted PEM key offline using
the pinned Cosign's import-key-pair command, encrypt the output, and test sign/verify before
uploading it. Never assume arbitrary key types/formats are supported. Validate an attached
certificate's public key matches the signing key and its chain against the organization's
CA policy. The pipeline checks certificate expiry, matching public key and any supplied
chain (which must include the trusted root). The implemented OCI verification pins the expected public key derived from
the protected signing key; it is not an independent X.509 organizational trust audit.
Private files live only in an umask-077 temporary directory with EXIT cleanup.
PR jobs receive none of these secrets. Missing keys/passwords fail publication.

Image: ghcr.io/wiesmak/tiket. Chart: oci://ghcr.io/wiesmak/charts/tiket.
helm push destination is oci://ghcr.io/wiesmak/charts.
Both immutable digests are signed and verified with Cosign. Store the expected public key
out of band; do not establish trust by downloading a key from an untrusted release alone.

```sh
cosign verify --key trusted-cosign.pub ghcr.io/wiesmak/tiket@sha256:<image-digest>
cosign verify --key trusted-cosign.pub ghcr.io/wiesmak/charts/tiket@sha256:<chart-digest>
cosign verify-blob --key trusted-cosign.pub --bundle SHA256SUMS.sigstore.json SHA256SUMS
sha256sum --check SHA256SUMS
```

Record digest/key identities for rotation. Publish the new public key through the trusted
channel, overlap verification policy while rotating, retain old keys for historical releases,
and revoke compromised trust explicitly. Never reuse a compromised private key.

The pipeline produces JARs, chart tgz, SHA256SUMS, signed checksum bundle, Syft image/JVM
CycloneDX SBOMs, license reporting and signed provenance/checksum metadata. Trivy fails on
HIGH/CRITICAL image vulnerabilities; do not add blanket ignores. Dependency/license findings
need owner review and documented narrow exceptions. License reporting is not legal approval.
Provenance describes source revision and published digests; no independent security audit or
SLSA certification is claimed. No keyless/OIDC permissions are needed for protected-key signing.
Only the GitHub Release creation job gets contents:write; OCI publishers get packages:write.

## Native image and unresolved release gates

Native support is experimental/unverified and no native artifact is published.
No native parity is claimed for Spring/gRPC/Flyway/TLS/tracing or financial reconstruction.
A GraalVM Java 25 toolchain and meaningful PostgreSQL/JWT/TLS/full financial parity are
required before adding native publishing. The JVM pipeline remains authoritative.

Phase 7 gates remain open: complete positive-role coverage of all 37 RPCs; shutdown under
sustained in-flight financial work; durable print trace propagation across restart; detailed
large-event query/CPU/lock profiling. The kind runner's pending-job recovery does not close
the sustained-shutdown/lease-interruption gates. Day locks remain a scaling constraint.
The suite verifies committed replay after restart; it does not inject network packet loss
at the precise commit/ack boundary or prove interrupted physical-printer delivery.
Legacy pre-V5 replay actor evidence and physical printer at-least-once delivery limitations
remain as documented in Phase 7. No financial formulas were changed.

## Validation evidence (2026-11-11)

Executed so far:
- Red Hat registry manifest inspected; Java 25.0.5 executed.
- Docker backend image built; Java ran as arbitrary UID with read-only root and tmpfs.
- Helm lint and the render/schema matrix passed.
- GitHub Actions YAML passed actionlint 1.7.7 locally.
- Node test dependencies audited: zero findings after patch upgrades.
- Clean uncached build passed in 8m41s: 34 core and 217 backend tests, zero failures,
  errors or skips, including the two MigrationOnlyTests and PostgreSQL 18 suites.
  All five separately requested Gradle commands subsequently exited zero.
  After the Guava patch, contracts build, all 217 backend tests and bootJar passed again
  in 5m53s. No financial formulas or V1-V5 migration bytes were changed.
- The first clean attempt hit a Windows JAR lock; a later incremental attempt hit a missing
  Kotlin classpath snapshot. Validation is being repeated with incremental compilation off.
- kind startup initially failed on cgroup v1; the explicit compatibility setting produced
  a Ready Kubernetes 1.35 node. An initial fixture argument bug was fixed. Cleanup was
  verified after retrying a transient Docker container-stop failure.

The full Gateway run passed on cluster tiket-82cc1049. It used a temporary PostgreSQL 18.1
PVC, actual Helm migration hook, two replicas, HTTPS JWKS, native h2c and direct TLS.
Authenticated event access and JWT denials went through an actual Envoy Gateway GRPCRoute.
Concurrent exact purchase/cashout replay, arbitrary-UID replacement, durable restart replay,
pending print recovery, DB readiness/liveness outage distinction and DB recovery passed.
The cluster was deleted successfully. The gateway port-forward readiness race found in
an earlier attempt was fixed and the passing run used the fix.

The complete Gateway/Docker run subsequently passed on
tiket-1c23a61b-95cc-4b62-bdbf-3b5652426527 (build/phase8-kind.log).
The final separate Ingress run passed on tiket-3977b427-8cbe-4907-af17-b59ca1dcd789
(build/phase8-kind-ingress-peers.log, exit status 0). It exercised real Traefik 37.4.0
Ingress gRPC traffic and explicitly forwarded concurrent purchase/cashout replay to
two distinct ready pods; both pairs of pod names are recorded in that log.
It also verified the failed-migration rollout gate, restricted runtime credentials,
restart/print recovery, DB outage/recovery and both standalone Docker transport modes.

Standalone Docker h2c and direct-TLS application startup, management readiness, gRPC/JWT
checks and clean SIGTERM completed as UID 1001234567:0 with a read-only root, tmpfs,
ALL capabilities dropped and no privilege escalation. Docker-only test certificate
material uses a temporary named volume with explicit POSIX permissions, fixing the
Windows bind-mount private-key permission problem found in an earlier attempt.
Earlier Ingress failures also exposed a local Helm repository-cache dependency, a
wrong IngressClass and a PostgreSQL temporary-init-server readiness race. The runner
now isolates Helm state, selects edge-traefik, and probes PostgreSQL over TCP.
Prior failure evidence is retained alongside the final passing evidence.

After these runs, kind reported no clusters; no tiket-prefixed test containers or
tiket.test-invocation-labeled certificate volumes remained.
Linux-hosted Actions has not been executed locally; the fixture certificate generator
uses the host numeric UID on Linux so files remain readable by the runner.
Syft generated image and JVM CycloneDX SBOMs and local unsigned SHA256SUMS; both SBOMs
identify Guava 33.7.2-jre. Eleven local artifact checksums verified successfully.

Trivy's executed scan failed with 29 HIGH records: 28 in the pinned Red Hat base plus
Guava CVE-2026-102554 in 33.6.0-jre. The latter is patched to 33.7.2-jre and regression-tested.
Red Hat's latest tag resolved to the same pinned base digest at inspection time.
The scanner reported no fixed versions for these base groups:

| Package group | Reported CVEs |
| --- | --- |
| expat | CVE-2026-115506, CVE-2026-76641 |
| Java 25 headless/crypto adapter | CVE-2026-91403 |
| json-glib | CVE-2026-120194 |
| krb5-libs | CVE-2026-112315 through CVE-2026-112323, CVE-2026-119079 |
| libblkid/libmount/libsmartcols/libuuid | CVE-2026-53613, CVE-2026-53615 |
| python3-pip-wheel | CVE-2026-57585 |
| sqlite-libs | CVE-2026-119373, CVE-2026-119374, CVE-2026-119379, CVE-2026-119586 |

These are scanner findings requiring remediation/vendor assessment, not accepted risk.
No ignore file or severity downgrade was added. CI/publication intentionally fail the
HIGH/CRITICAL scan gate until a reviewed patched base or narrowly approved policy resolves
them. Consequently Phase 8 does not meet every release-readiness definition-of-done item.
The original report is build/phase8-artifacts/vulnerabilities.json. Local SBOM/checksum
generation is unsigned; publication adds signatures only after every required gate passes.

No GHCR publication, remote Actions run, signing with owner credentials, OpenShift deployment,
CNPG failover or Podman test has been executed. native-image was not installed; no native
compilation or parity test ran. There are no published release digests to report.

Phase 9 work is limited to a future comprehensive documentation consolidation; it has not
been started here. This operational document intentionally tracks incomplete evidence.
