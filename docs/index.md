# Tiket
Tiket is a Kotlin/JVM parimutuel horse-betting backend and reusable calculation library. It supports event administration, structured multi-leg coupons, four independent pools, refunds, verified settlement, final accounting and durable print dispatch.

Start with [local development](getting-started/local-development.md), then follow the [gRPC workflow](api/examples.md). Operators should read [deployment](deployment/helm.md), [security](api/authentication.md) and [recovery](operations/recovery.md).

There is no graphical cashier application, payment transfer integration or automatic physical cash payout. A registered cashout is an accounting fact, not proof of disbursement. Development snapshots are not production approved.

[License](license.md) · [Kotlin API](library-api.md) · [Protobuf schema](api/grpc-reference.md)
