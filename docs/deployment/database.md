# Database and migrations

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


## Migration reference

| Migration | Purpose |
| --- | --- |
| V1 | Foundation/Flyway baseline |
| V2 | Domain, ledger, pools, coupons and financial integrity |
| V3 | Ordered administration and pre-start funding adjustments |
| V4 | Print jobs/attempts and query indexes |
| V5 | Operational audit and command actor binding |

Relationships: race_day -> race -> race_runner, race_state, race_result -> race_result_finisher;
race -> pool_rules -> pool_state -> pool_selection_units;
race_day -> player_entry -> account_balance, account_movement;
coupon -> coupon_leg -> leg_resolution; pool_settlement -> payout.
Composite foreign keys bind race-day, race, player, pool and coupon contexts.

Definitions use public core constructors. CouponLeg.reconstitute and Coupon.reconstitute validate
frozen purchased expansions. Original selections are normalized as a variant plus ordered choice
rows (HORSE/WALL or BOX), with purchase eligibility stored separately. No JVM serialization.
Atomic selections use ordered UUID columns and core AtomicBet.create; Quinella is canonicalized.
Runner ordinals preserve display order; leg ordinals are zero-based core identities.

AccountSnapshot.reconstitute is the only public historical account factory. It requires full
ordered ledger replay and validates the stored AccountProjection. There is no safe public factory
for a policy-ready snapshot from projection totals alone. Efficient totals reads return the
AccountProjection value, never an AccountSnapshot. Ordinary domain policy calls currently replay
history; a separately reviewed core API will be needed for bounded-cost historical snapshots.
Opening funding starts in immutable player_entry; V3 adds append-only pre-start adjustments. No extra wallets.

RaceResult constructors do not validate finishers; adapters validate roster membership, uniqueness,
revision and verified arity. Exceptional results are stored but cannot be used by normal core
settlement policy. PoolSnapshot needs adapter checks for unit-stake equality. PoolSettlement and
LegResolution public records likewise require adapter validation.

All compound writes run in Spring transactions. Application services must wrap complete plans in
PersistenceTransactions.required: purchase writes header, legs, debit, pool projections and
idempotency; refunds write terminal resolutions, credits, pool reductions and state; settlement
writes four pools, outcomes, credits and state; cashout writes both terminal movements and projection.
Application services compose the storage operations described below.

Lock order: day state when event-wide coordination is needed, then race state, accounts sorted
by UUID text, pools sorted by bet-type name, then subordinate records. Lock helpers require an
active transaction. Version updates compare expected version and advance by one per domain mutation (account batches advance by the number of core steps, with cashout counting as one); zero affected
rows throw StaleVersionException. Acquire locks before loading mutable snapshots. Do not combine
unlocked reads across multiple SQL statements when a consistent aggregate is needed.

The ledger is authoritative. Account writes verify the complete resulting snapshot against replay,
append only the new suffix, and update projection with a version check in the same transaction.
Core enforces movement semantics. PostgreSQL rejects duplicate movement keys, coupon stakes,
refunds, winnings, payouts, settlements and terminal resolutions. Historical rows reject UPDATE
and DELETE through triggers; schema owners can disable triggers, so production runtime roles
must not own tables or have DDL rights. Migration credentials should be separate.

Idempotency executes inside the business transaction: INSERT ON CONFLICT DO NOTHING waits for
a competing transaction. A committed matching fingerprint replays the stored stable reference;
a different fingerprint fails. A failed transaction removes its reservation and all effects.
Pending reservations are never committed: a deferred constraint trigger checks completion.
The callback must return a durable result reference. External effects have no exactly-once guarantee.
Scope, operation and key are caller-supplied stable identifiers; fingerprints must cover canonical
validated request fields, including POS/cashier metadata where relevant.

Database checks enforce basic ranges, uniqueness, roster ownership and parent context. Core and
application services enforce lifecycle authorization, selection expansion, complete four-pool
settlement, legitimate financial plans and final cashout eligibility. Repository methods are storage
primitives, not permission checks. Setup insertion does not authorize late enrollment.

Indexes cover parent and ordered history lookups. Aggregate loads use multiple queries and should
run under locks or repeatable-read for consistency. Ledger replay is currently unbounded. Large
race coupon scans and full pool replacement may need batching in later measured optimizations.

## Mapping reference

| Domain value | Durable representation and reconstruction |
| --- | --- |
| RaceDayDefinition, RaceDayRules | race_day plus ordered races and all four pool_rules per race; public constructors validate the complete definition |
| RaceDayState | race_day_state phase and version |
| RaceDefinition, RaceRunner | race and race_runner ordered by ordinal; constructor validates roster identities and program numbers |
| RaceState | race_state plus race_scratch and selected result revision |
| RaceResult | race_result plus finishers ordered by position; proposal sequence preserves revision insertion order |
| PoolRules | pool_rules unit stake and take basis points through Money and TakeRate |
| PoolSnapshot | pool_state plus canonical pool_selection_units; adapter verifies stake and total units before construction |
| PlayerEntry | player_entry opening paid/freebet through Money |
| AccountProjection | account_balance totals, version and finalization flag; display-only projection |
| AccountSnapshot | full AccountSnapshot.reconstitute replay, compared to AccountProjection and cashout flag |
| AccountMovement | account_movement stable scoped key; identity sequence gives deterministic order; timestamp is backend metadata |
| CouponSelection | coupon.selection_kind plus coupon_selection zero-based positions; HORSE/WALL preserve positional meaning, BOX preserves explicit horses |
| Coupon, CouponLeg | coupon header, coupon_leg and coupon_eligibility; public reconstitution validates historical expansion without current roster eligibility |
| LegResolution | one terminal leg_resolution per BetLegId; active is represented by absence |
| PoolSettlement | settlement groups four pool_settlement rows with a verified result revision and per-pool version |
| Payout | payout ties a winning resolution to its exact account movement, player and amount |
| PurchasePlan, RefundPlan, RaceSettlementPlan | transient decisions, not serialized; persist their coupons, movements, projections, resolutions, settlements and resulting states atomically |

Deferred database checks reject incomplete coupon headers and settlements at commit. A result revision
can only change by verifying an otherwise identical proposal. Payout inserts must reference the exact
matching winnings movement and won resolution. Normal financial formulas remain in core.