# Architecture
Tiket is one executable backend and five libraries in a single Gradle build.

| Module | Responsibility |
| --- | --- |
| tiket-core | Pure immutable domain, policies, expansion, pricing, refunds, settlement and accounts |
| tiket-contracts | Protobuf definitions and generated Java gRPC messages/stubs |
| tiket-backend | Spring Boot, native Netty gRPC, application transactions, JDBC, JWT and operations |
| tiket-print-api | Immutable receipt data and TicketPrinter contract |
| tiket-print-console | Standard output adapter |
| tiket-print-serial | Java2D raster layout, escpos-coffee and jSerialComm |

Requests flow from generated gRPC adapters to application services, core decisions and explicit JDBC repositories. Core has no Spring, database, transport, printing, clock reads or ID generation. Adapters translate protobuf values rather than leaking transport into the domain.

PostgreSQL holds immutable definitions, original coupons and legs, append-only ledger/resolutions, mutable projections, idempotency acknowledgements and print outbox. Transactions lock day, race, sorted player accounts, deterministic pools, then child records. Optimistic versions detect stale clients. Purchase commits all legs, debit, target pool and original print job together; printing runs after commit and cannot undo a wager.

Settlement reconciles all four pools against historical legs and writes outcomes, credits, payouts and race state atomically. Completion and closure compute authoritative financial proofs while holding the day lock. These operations serialize across replicas; adding replicas does not imply linear throughput.
