# Kotlin library API
The assembled static site includes the <a href="../kotlin/index.html">browsable Kotlin API</a>. Generate it with `:dokkaGenerate` before assembling the site.

The supported reusable modules are core, print-api, print-console and print-serial. Backend application services, persistence repositories and security interceptors are internal implementation, not supported extension contracts.

Core APIs accept immutable snapshots and return validated decisions or plans. A successful plan is not a reservation: integrations must atomically enforce its versions and effects. The [domain model](concepts/domain-model.md) explains validated reconstitution.
