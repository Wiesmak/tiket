# gRPC API
All eight services are unary in `pl.umamusume.tiket.api.v1`. Java classes use `pl.umamusume.tiket.contracts.v1`. The checked-in source is `tiket-contracts/src/main/proto/pl/umamusume/tiket/api/v1/{system,administration,betting,account}.proto`.

The [generated schema](grpc-reference.md) lists every request, response, field, enum and service. Use it together with [authentication](authentication.md), [errors and replay](errors-and-idempotency.md), and the [worked workflow](examples.md). Schema availability does not authorize a call.

Identifiers are UUID strings, distinct from race numbers and runner program-number strings. Dates use ISO local dates; event zones are IANA zone IDs. Stored timestamps are UTC ISO instant strings. Monetary fields are signed protobuf int64 values validated as nonnegative integer currency units; quantities and unit stakes must be positive. JSON int64 values should be strings to preserve precision.

Proto3 scalar defaults are not presence: omitted scalar strings/numbers become empty/zero. Explicit `optional` fields carry presence (notably expected_account_version); message and oneof fields also carry presence. Repeated fields default to empty. Unspecified zero enum values are sentinels and are not valid pool/result choices. Set exactly one positional/box variant and exactly one horse_id/wall choice per position; a wall must be true.

Commands require idempotency keys and applicable expected versions. Queries do not reserve state. Quote confirmation binds recomputed pricing and expansion; it is neither a signature nor authorization.
