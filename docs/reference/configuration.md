# Configuration reference

Configuration is external Spring Boot configuration. Commit no passwords, tokens, certificates or private keys. Chart values supply the same settings through ConfigMaps, external Secrets and mounted files.

| Setting | Default/requirement |
| --- | --- |
| DB_URL | localhost PostgreSQL JDBC with connectTimeout=5/socketTimeout=30; production must use verify-full TLS and correct CA path |
| DB_USERNAME / DB_PASSWORD | Required external credentials |
| DB_POOL_SIZE / DB_CONNECTION_TIMEOUT | 10 connections / 5000 ms; multiply pool capacity by replica count |
| JWT_ISSUER / JWT_JWKS_URI | Required HTTPS issuer and RS256 JWKS endpoints |
| JWT_AUDIENCE | Required expected audience |
| SPRING_PROFILES_ACTIVE | production for deployed workloads; production rejects test/dev combinations |
| TIKET_TRANSPORT | production requires direct-tls or gateway-h2c |
| GRPC_ADDRESS / GRPC_PORT | 127.0.0.1 / 9090 |
| GRPC_TLS_ENABLED | Direct production TLS must explicitly enable it |
| SPRING_GRPC_SERVER_SSL_BUNDLE | Required SSL bundle for direct-tls |
| MANAGEMENT_ADDRESS / MANAGEMENT_PORT | 127.0.0.1 / 9091; isolate even when bound to pod interfaces |
| GRPC_MAX_MESSAGE | 1MB; metadata budget is 8KB |
| tiket.limits.concurrent | 32 active calls per process |
| tiket.limits.requests-per-second | 60 per authenticated subject per process |
| tiket.database.transaction-timeout-seconds | 20 seconds |
| tiket.printing.enabled / backend | true / console |
| tiket.printing.poll-millis / max-attempts | 1000 / 5 |
| tiket.printing.lease-seconds | 60 console; explicit at least 300 for serial; maximum 3600 |
| TRACING_EXPORT_ENABLED | false |
| OTEL_EXPORTER_OTLP_TRACES_ENDPOINT | Optional authorized collector |
| TIKET_ENVIRONMENT | unspecified; structured log environment label |

Use Spring property names in YAML or command-line configuration for dotted settings. See [printing](../guides/printing.md) for serial routing, profile defaults and lease sizing. Direct TLS uses standard Spring SSL PEM bundles; [authentication](../api/authentication.md) shows exact properties and mTLS configuration.

The chart defaults to private exposure and external PostgreSQL/JWT. It disables application-start Flyway and gates migration separately. [Helm](../deployment/helm.md) and [database](../deployment/database.md) explain secret names, transport, networking and migration ordering.

Reflection is disabled; there is no configuration switch in this guide to disable authentication for a public service. Metrics and probes have no application bearer authentication: binding and network isolation are mandatory.
