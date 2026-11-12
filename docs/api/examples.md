# Worked gRPC client flow

This example creates one day, three runners (#2, #3, #4), four pools and one player, then buys an Exacta box, settles it and closes the day. Use an isolated database. The UUIDs are fixed so that the JSON is reviewable; choose fresh UUIDs for another event. CreateRaceDay is one complete definition: there is no subsequent race/runner editing RPC.

Configure HTTPS issuer grants **before creating the day**, including the new day UUID. Use separate tokens for EVENT_ADMIN, CASHIER, RESULT_SUBMITTER, RESULT_VERIFIER and SETTLEMENT_OPERATOR. The cashier needs the player UUID in tiket_players, POS "1" in tiket_pos and tiket_cashier="demo-cashier". Each token needs the event UUID in tiket_events. See [permissions](authentication.md). No token is included here.

These are schema-validated fixtures, not recorded server responses. For each step, read the response and substitute actual versions before the next mutation. The fixture versions describe an otherwise idle isolated event; concurrent actions can change them. Reusing a key with edited versions is a conflict: use a new key for a new intended request.

## Invoke a call

Bash:
```sh
export TIKET_ENDPOINT='tiket.example:443'
export TIKET_CA='/path/to/trusted-ca.pem'
export ADMIN_TOKEN='<provider-issued token>'
grpcurl -cacert "$TIKET_CA" -H "authorization: Bearer $ADMIN_TOKEN" \
  -import-path tiket-contracts/src/main/proto \
  -proto pl/umamusume/tiket/api/v1/administration.proto \
  -d @ "$TIKET_ENDPOINT" pl.umamusume.tiket.api.v1.RaceDayService/CreateRaceDay \
  < docs/api/fixtures/create.json
```

PowerShell:
```powershell
$env:TIKET_ENDPOINT = 'tiket.example:443'
$env:TIKET_CA = 'C:/certs/trusted-ca.pem'
$env:ADMIN_TOKEN = '<provider-issued token>'
Get-Content -Raw docs/api/fixtures/create.json | grpcurl.exe -cacert $env:TIKET_CA -H "authorization: Bearer $env:ADMIN_TOKEN" -import-path tiket-contracts/src/main/proto -proto pl/umamusume/tiket/api/v1/administration.proto -d @ $env:TIKET_ENDPOINT pl.umamusume.tiket.api.v1.RaceDayService/CreateRaceDay
```

For mTLS add `-cert client.crt -key client.key`; JWT still applies. Never use `-insecure` to bypass certificate checks. Server reflection is disabled to limit introspection; supply the actual import path and appropriate proto file. Account calls use account.proto; betting/printing use betting.proto; result/administration use administration.proto.

## Execute in order

Use the invocation above with the indicated fixture, method and token. Every fully qualified method starts with `pl.umamusume.tiket.api.v1.`.

| Step and request | Method | Token | Response/use |
| --- | --- | --- | --- |
| 1. [create.json](fixtures/create.json) | `RaceDayService/CreateRaceDay` | ADMIN | DRAFT/version 0; full definition supplied |
| 2. [publish.json](fixtures/publish.json) | `RaceDayService/PublishRaceDay` | ADMIN | READY/version 1; definition frozen |
| 3. [enroll.json](fixtures/enroll.json) | `EnrollmentService/EnrollPlayer` | CASHIER | Opening balance 1100/account version 0 |
| 4. [fund.json](fixtures/fund.json) | `EnrollmentService/AddOpeningFunding` | CASHIER | Opening balance 1200/account version 1 |
| 5. [start.json](fixtures/start.json) | `RaceDayService/StartRaceDay` | ADMIN | RUNNING/day version 2; enrollment frozen |
| 6. [open.json](fixtures/open.json) | `RaceService/OpenBetting` | ADMIN | BETTING_OPEN/race version 1 |
| 7. [quote.json](fixtures/quote.json) | `BettingService/QuoteCoupon` | CASHIER | Six combinations, 12 units, total stake 120; copy confirmation and versions |
| 8. [purchase.json](fixtures/purchase.json) | `BettingService/PurchaseCoupon` | CASHIER | Replace quote_confirmation with returned confirmation; balance 1080 and account version 2 |
| 9. [print.json](fixtures/print.json) | `PrintingService/GetPrintStatus` | CASHIER | Replace print_job_id with PurchaseCouponResponse.print_job_id; inspect current dispatch |
| 10. [close-betting.json](fixtures/close-betting.json) | `RaceService/CloseBetting` | ADMIN | BETTING_CLOSED/race version 2 |
| 11. [result.json](fixtures/result.json) | `ResultService/SubmitResult` | SUBMITTER | RESULT_PROPOSED; selected revision official-1 |
| 12. [verify.json](fixtures/verify.json) | `ResultService/VerifyResult` | VERIFIER | RESULT_VERIFIED; at least three distinct eligible finishers |
| 13. [settle.json](fixtures/settle.json) | `ResultService/SettleRace` | SETTLEMENT | Four pools settled atomically; one Exacta winner unit pair pays 102; account version 3 |
| 14. [finalize.json](fixtures/finalize.json) | `RaceDayService/FinalizeRaceDay` | ADMIN | FINALIZING/day version 3; no more betting |
| 15. [complete.json](fixtures/complete.json) | `RaceDayService/CompleteRaceDay` | ADMIN | COMPLETED/day version 4 after terminal evidence proof |
| 16. [withdrawal.json](fixtures/withdrawal.json) | `AccountService/GetWithdrawalQuote` | CASHIER | Copy race_day_version and account.account_version; inspect withdrawable/forfeitable |
| 17. [cashout.json](fixtures/cashout.json) | `AccountService/CashOut` | CASHIER | Replace versions from withdrawal quote; record amounts, then separately reconcile disbursement |
| 18. [close-day.json](fixtures/close-day.json) | `RaceDayService/CloseRaceDay` | ADMIN | CLOSED/version 5 only after every account is cashed out |

The purchase spends 120 integer units. With one winning Exacta leg (2 units), valid pool 120 and 1500 basis points take, distributable=102, dividend=51, paid=102, house=18. Other pools are empty. No winning stake is returned separately. Balance after settlement is 1182, qualifying turnover 120 unlocks all 100 promotional units, and final withdrawal is 1182 with zero forfeiture.

## Optional scratch instead

Before result verification, call ScratchHorse with `{"command": <current RaceCommand>, "horse_id": <runner UUID>}`. Do not send those angle-bracket placeholders literally. Only active legs containing the horse refund; refresh quote/state and do not reuse the original purchase. With only three runners, scratching one prevents the normal three-finisher verification flow above. Use VoidRace with a fresh RaceCommand to refund remaining legs and resolve the race instead. Never invent a dead-heat or disqualification payout.

## Query and failure handling

SystemService/Ping uses system.proto and an empty request: `grpcurl -cacert "$TIKET_CA" -import-path tiket-contracts/src/main/proto -proto pl/umamusume/tiket/api/v1/system.proto -d '{}' "$TIKET_ENDPOINT" pl.umamusume.tiket.api.v1.SystemService/Ping`. It is public and proves process/API availability, not database readiness.

GetRaceDay, GetRace/GetRaceStatus, GetEnrollment/GetAccount and GetCoupon provide authoritative status. Use ListPlayerCoupons, ListAccountMovements, ListResultRevisions and ListRaceLegResolutions with their schema's pagination. ListPlayers and GetSettlement require auditor privileges. ReprintCoupon requires cashier scope and a fresh durable key; it creates another job, not another bet.

A timeout can follow a successful commit. Replay identical bytes/key as the same subject before taking another action. A stale version returns ABORTED; stale quote confirmation or illegal lifecycle returns FAILED_PRECONDITION; changed key payload/duplicate UUID returns ALREADY_EXISTS. Missing/expired tokens and invalid grants fail before business execution. [Errors and idempotency](errors-and-idempotency.md) describes recovery.

Print status DISPATCHED means adapter transmission succeeded, not that paper was acknowledged. Pending printing does not prevent financial closure.
