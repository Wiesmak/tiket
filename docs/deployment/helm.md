# Helm, Kubernetes and OpenShift

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

## Upgrades and rollback

For a private deployment, prepare external Secrets `tiket-db-runtime` and `tiket-db-migration` with `username`/`password`, and a `tiket-db-ca` Secret containing `ca.crt`. Supply reviewed values such as:

```yaml
image:
  repository: ghcr.io/wiesmak/tiket
  digest: sha256:<verified-image-digest>
database:
  url: jdbc:postgresql://postgres-rw:5432/tiket?sslmode=verify-full&sslrootcert=/run/db/ca.crt&connectTimeout=5&socketTimeout=30
  runtimeSecret: tiket-db-runtime
  migrationSecret: tiket-db-migration
  tlsSecret: tiket-db-ca
jwt:
  issuer: https://identity.example/issuer
  jwksUri: https://identity.example/jwks
  audience: tiket
transport: gateway-h2c
exposure:
  mode: none
```

Replace every placeholder and ensure private h2c reachability is limited to trusted clients. To enable Gateway exposure, use the earlier gateway example and provision an external TLS listener. Use `helm upgrade --install tiket ./tiket-<verified-version>.tgz --kubeconfig <reviewed-kubeconfig> -f production.yaml --wait --timeout 10m` only after signature/checksum verification. No current development snapshot becomes production-approved merely through installation.

Verify both immutable digests before installation. Review values, take a tested database backup, run the controlled migration gate, then wait for readiness and exercise authorized reads before opening traffic. Use an explicit kubeconfig. Keep old image/chart digests and values. Helm rollback changes workloads, not Flyway history; prove schema compatibility before rolling back application code. Never edit applied V1–V5 migrations.
