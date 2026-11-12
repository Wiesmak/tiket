# Errors, versions and idempotency
Persist a fresh key for each intended mutation before sending it. After a timeout, retry the identical request with that same key and authenticated subject. A committed retry returns the original acknowledgement, including historical versions/balance/print state. Query current state separately. Changed request content under the same scoped key returns ALREADY_EXISTS.

Versions are concurrency guards. Fetch the current day/race/account state, quote, then purchase using returned versions and confirmation. On ABORTED, refresh and obtain user approval for a new quote; use a new key for the changed request. Never silently change financial terms.

| Status | Typical cause | Client action |
| --- | --- | --- |
| UNAUTHENTICATED | Missing, expired, wrong issuer/audience or invalid JWT | Renew valid credentials |
| PERMISSION_DENIED | Role or event/player/POS grant mismatch; inaccessible opaque resource | Correct provider grants; do not probe identities |
| INVALID_ARGUMENT | UUID/date/enum/selection/amount invalid or overflow | Correct input |
| NOT_FOUND | Missing authorized record | Check identity |
| ALREADY_EXISTS | Duplicate identity or changed payload for a key | Reconcile original request |
| ABORTED | Stale version or recognized concurrency failure | Reload state |
| FAILED_PRECONDITION | Closed betting, stale confirmation, insufficient funds, frozen funding or illegal lifecycle | Correct workflow |
| RESOURCE_EXHAUSTED | Expansion limit, request budget or rate limit | Bound requests/back off |
| UNAVAILABLE | Draining service or dependency outage | Retry reads; replay identical commands |
| INTERNAL | Sanitized unexpected failure or stored inconsistency | Escalate; do not edit financial rows |

Failed transactions roll back reservations and business effects together. New authenticated commands bind replay to a hashed subject in command_actor. Legacy pre-audit records lack actor evidence and remain subject to current resource authorization. No historical actor can be reconstructed safely.

Coupon/result-revision lists accept page_size 1–100; terminal leg resolution pages accept 1–500. These use offset; account movements accept 1–100 and use exclusive after_sequence. Enrollment and authoritative account/closure reconstruction can still read unbounded histories.
