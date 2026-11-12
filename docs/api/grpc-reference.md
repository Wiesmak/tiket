# Generated Protobuf schema

[Usage and security](overview.md) · [Client workflow](examples.md)

Source SHA-256: 519a54e3b7831dedb685cf31df0f0db9929b6779002fd809fd33a5ac0c231d61

# Protocol Documentation
<a name="top"></a>

## Table of Contents

- [pl/umamusume/tiket/api/v1/account.proto](#pl_umamusume_tiket_api_v1_account-proto)
    - [AccountInformation](#pl-umamusume-tiket-api-v1-AccountInformation)
    - [AccountMovements](#pl-umamusume-tiket-api-v1-AccountMovements)
    - [AccountMovementsRequest](#pl-umamusume-tiket-api-v1-AccountMovementsRequest)
    - [AccountQuery](#pl-umamusume-tiket-api-v1-AccountQuery)
    - [CashOutRequest](#pl-umamusume-tiket-api-v1-CashOutRequest)
    - [CashoutReceipt](#pl-umamusume-tiket-api-v1-CashoutReceipt)
    - [FinancialMovement](#pl-umamusume-tiket-api-v1-FinancialMovement)
    - [WithdrawalQuoteInformation](#pl-umamusume-tiket-api-v1-WithdrawalQuoteInformation)
  
    - [AccountMovementKind](#pl-umamusume-tiket-api-v1-AccountMovementKind)
  
    - [AccountService](#pl-umamusume-tiket-api-v1-AccountService)
  
- [pl/umamusume/tiket/api/v1/administration.proto](#pl_umamusume_tiket_api_v1_administration-proto)
    - [AddOpeningFundingRequest](#pl-umamusume-tiket-api-v1-AddOpeningFundingRequest)
    - [CreateRaceDayRequest](#pl-umamusume-tiket-api-v1-CreateRaceDayRequest)
    - [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement)
    - [DayCommand](#pl-umamusume-tiket-api-v1-DayCommand)
    - [DayQuery](#pl-umamusume-tiket-api-v1-DayQuery)
    - [EnrollPlayerRequest](#pl-umamusume-tiket-api-v1-EnrollPlayerRequest)
    - [EnrollmentInformation](#pl-umamusume-tiket-api-v1-EnrollmentInformation)
    - [EnrollmentQuery](#pl-umamusume-tiket-api-v1-EnrollmentQuery)
    - [OfficialResult](#pl-umamusume-tiket-api-v1-OfficialResult)
    - [PlayerFinancialSummary](#pl-umamusume-tiket-api-v1-PlayerFinancialSummary)
    - [PlayerList](#pl-umamusume-tiket-api-v1-PlayerList)
    - [PoolConfiguration](#pl-umamusume-tiket-api-v1-PoolConfiguration)
    - [PoolRefundSummary](#pl-umamusume-tiket-api-v1-PoolRefundSummary)
    - [PoolSettlementSummary](#pl-umamusume-tiket-api-v1-PoolSettlementSummary)
    - [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement)
    - [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand)
    - [RaceConfiguration](#pl-umamusume-tiket-api-v1-RaceConfiguration)
    - [RaceDayConfiguration](#pl-umamusume-tiket-api-v1-RaceDayConfiguration)
    - [RaceDayInformation](#pl-umamusume-tiket-api-v1-RaceDayInformation)
    - [RaceInformation](#pl-umamusume-tiket-api-v1-RaceInformation)
    - [RaceLegResolutions](#pl-umamusume-tiket-api-v1-RaceLegResolutions)
    - [RaceLegResolutionsRequest](#pl-umamusume-tiket-api-v1-RaceLegResolutionsRequest)
    - [RaceList](#pl-umamusume-tiket-api-v1-RaceList)
    - [RaceQuery](#pl-umamusume-tiket-api-v1-RaceQuery)
    - [RefundAcknowledgement](#pl-umamusume-tiket-api-v1-RefundAcknowledgement)
    - [ResultAcknowledgement](#pl-umamusume-tiket-api-v1-ResultAcknowledgement)
    - [ResultRevisions](#pl-umamusume-tiket-api-v1-ResultRevisions)
    - [ResultRevisionsRequest](#pl-umamusume-tiket-api-v1-ResultRevisionsRequest)
    - [Runner](#pl-umamusume-tiket-api-v1-Runner)
    - [ScratchHorseRequest](#pl-umamusume-tiket-api-v1-ScratchHorseRequest)
    - [SettleRaceRequest](#pl-umamusume-tiket-api-v1-SettleRaceRequest)
    - [SettlementInformation](#pl-umamusume-tiket-api-v1-SettlementInformation)
    - [SettlementQuery](#pl-umamusume-tiket-api-v1-SettlementQuery)
    - [SubmitResultRequest](#pl-umamusume-tiket-api-v1-SubmitResultRequest)
    - [TerminalLegResolution](#pl-umamusume-tiket-api-v1-TerminalLegResolution)
    - [VerifyResultRequest](#pl-umamusume-tiket-api-v1-VerifyResultRequest)
  
    - [DayPhase](#pl-umamusume-tiket-api-v1-DayPhase)
    - [NoWinnerPolicy](#pl-umamusume-tiket-api-v1-NoWinnerPolicy)
    - [OfficialResultKind](#pl-umamusume-tiket-api-v1-OfficialResultKind)
    - [OfficialResultStatus](#pl-umamusume-tiket-api-v1-OfficialResultStatus)
    - [PoolType](#pl-umamusume-tiket-api-v1-PoolType)
    - [RacePhase](#pl-umamusume-tiket-api-v1-RacePhase)
    - [TerminalLegOutcome](#pl-umamusume-tiket-api-v1-TerminalLegOutcome)
  
    - [EnrollmentService](#pl-umamusume-tiket-api-v1-EnrollmentService)
    - [RaceDayService](#pl-umamusume-tiket-api-v1-RaceDayService)
    - [RaceService](#pl-umamusume-tiket-api-v1-RaceService)
    - [ResultService](#pl-umamusume-tiket-api-v1-ResultService)
  
- [pl/umamusume/tiket/api/v1/betting.proto](#pl_umamusume_tiket_api_v1_betting-proto)
    - [BettingSelection](#pl-umamusume-tiket-api-v1-BettingSelection)
    - [BoxSelection](#pl-umamusume-tiket-api-v1-BoxSelection)
    - [CouponInformation](#pl-umamusume-tiket-api-v1-CouponInformation)
    - [CouponList](#pl-umamusume-tiket-api-v1-CouponList)
    - [CouponPricing](#pl-umamusume-tiket-api-v1-CouponPricing)
    - [CouponQuery](#pl-umamusume-tiket-api-v1-CouponQuery)
    - [ListPlayerCouponsRequest](#pl-umamusume-tiket-api-v1-ListPlayerCouponsRequest)
    - [Position](#pl-umamusume-tiket-api-v1-Position)
    - [PositionalSelection](#pl-umamusume-tiket-api-v1-PositionalSelection)
    - [PrintAttemptInformation](#pl-umamusume-tiket-api-v1-PrintAttemptInformation)
    - [PrintStatus](#pl-umamusume-tiket-api-v1-PrintStatus)
    - [PrintStatusQuery](#pl-umamusume-tiket-api-v1-PrintStatusQuery)
    - [PurchaseCouponRequest](#pl-umamusume-tiket-api-v1-PurchaseCouponRequest)
    - [PurchaseCouponResponse](#pl-umamusume-tiket-api-v1-PurchaseCouponResponse)
    - [PurchasedLeg](#pl-umamusume-tiket-api-v1-PurchasedLeg)
    - [QuoteCouponRequest](#pl-umamusume-tiket-api-v1-QuoteCouponRequest)
    - [QuoteCouponResponse](#pl-umamusume-tiket-api-v1-QuoteCouponResponse)
    - [ReprintCouponRequest](#pl-umamusume-tiket-api-v1-ReprintCouponRequest)
  
    - [DispatchStatus](#pl-umamusume-tiket-api-v1-DispatchStatus)
    - [PrintJobType](#pl-umamusume-tiket-api-v1-PrintJobType)
  
    - [BettingService](#pl-umamusume-tiket-api-v1-BettingService)
    - [PrintingService](#pl-umamusume-tiket-api-v1-PrintingService)
  
- [pl/umamusume/tiket/api/v1/system.proto](#pl_umamusume_tiket_api_v1_system-proto)
    - [PingRequest](#pl-umamusume-tiket-api-v1-PingRequest)
    - [PingResponse](#pl-umamusume-tiket-api-v1-PingResponse)
  
    - [SystemService](#pl-umamusume-tiket-api-v1-SystemService)
  
- [Scalar Value Types](#scalar-value-types)



<a name="pl_umamusume_tiket_api_v1_account-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## pl/umamusume/tiket/api/v1/account.proto



<a name="pl-umamusume-tiket-api-v1-AccountInformation"></a>

### AccountInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| currency | [string](#string) |  |  |
| phase | [DayPhase](#pl-umamusume-tiket-api-v1-DayPhase) |  |  |
| opening_paid | [int64](#int64) |  |  |
| opening_promotional | [int64](#int64) |  |  |
| accepted_stakes | [int64](#int64) |  |  |
| refunded_stakes | [int64](#int64) |  |  |
| qualifying_turnover | [int64](#int64) |  |  |
| credited_winnings | [int64](#int64) |  |  |
| balance | [int64](#int64) |  |  |
| account_version | [int64](#int64) |  |  |
| cashed_out | [bool](#bool) |  |  |






<a name="pl-umamusume-tiket-api-v1-AccountMovements"></a>

### AccountMovements



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| movements | [FinancialMovement](#pl-umamusume-tiket-api-v1-FinancialMovement) | repeated |  |
| next_sequence | [int64](#int64) | optional |  |






<a name="pl-umamusume-tiket-api-v1-AccountMovementsRequest"></a>

### AccountMovementsRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| account | [AccountQuery](#pl-umamusume-tiket-api-v1-AccountQuery) |  |  |
| page_size | [int32](#int32) |  |  |
| after_sequence | [int64](#int64) |  |  |






<a name="pl-umamusume-tiket-api-v1-AccountQuery"></a>

### AccountQuery



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-CashOutRequest"></a>

### CashOutRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| expected_day_version | [int64](#int64) |  |  |
| expected_account_version | [int64](#int64) |  |  |
| idempotency_key | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-CashoutReceipt"></a>

### CashoutReceipt



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| currency | [string](#string) |  |  |
| withdrawn | [int64](#int64) |  |  |
| promotional_forfeiture | [int64](#int64) |  |  |
| balance | [int64](#int64) |  |  |
| account_version | [int64](#int64) |  |  |
| cashed_out_at | [string](#string) |  |  |
| receipt_reference | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-FinancialMovement"></a>

### FinancialMovement



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| sequence | [int64](#int64) |  |  |
| movement_key | [string](#string) |  |  |
| kind | [AccountMovementKind](#pl-umamusume-tiket-api-v1-AccountMovementKind) |  |  |
| amount | [int64](#int64) |  |  |
| created_at | [string](#string) |  |  |
| coupon_id | [string](#string) | optional |  |
| leg_ordinal | [int32](#int32) | optional |  |
| settlement_id | [string](#string) | optional |  |






<a name="pl-umamusume-tiket-api-v1-WithdrawalQuoteInformation"></a>

### WithdrawalQuoteInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| account | [AccountInformation](#pl-umamusume-tiket-api-v1-AccountInformation) |  |  |
| unlocked_promotional | [int64](#int64) |  |  |
| withdrawable | [int64](#int64) |  |  |
| forfeitable | [int64](#int64) |  |  |
| race_day_version | [int64](#int64) |  |  |





 


<a name="pl-umamusume-tiket-api-v1-AccountMovementKind"></a>

### AccountMovementKind


| Name | Number | Description |
| ---- | ------ | ----------- |
| ACCOUNT_MOVEMENT_KIND_UNSPECIFIED | 0 |  |
| COUPON_STAKE | 1 |  |
| LEG_REFUND | 2 |  |
| WINNINGS | 3 |  |
| FINAL_WITHDRAWAL | 4 |  |
| PROMOTIONAL_FORFEITURE | 5 |  |


 

 


<a name="pl-umamusume-tiket-api-v1-AccountService"></a>

### AccountService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| GetAccount | [AccountQuery](#pl-umamusume-tiket-api-v1-AccountQuery) | [AccountInformation](#pl-umamusume-tiket-api-v1-AccountInformation) | Get Account for authorized resources. |
| GetWithdrawalQuote | [AccountQuery](#pl-umamusume-tiket-api-v1-AccountQuery) | [WithdrawalQuoteInformation](#pl-umamusume-tiket-api-v1-WithdrawalQuoteInformation) | Get Withdrawal Quote for authorized resources. |
| CashOut | [CashOutRequest](#pl-umamusume-tiket-api-v1-CashOutRequest) | [CashoutReceipt](#pl-umamusume-tiket-api-v1-CashoutReceipt) | Record withdrawal and forfeiture; no money is disbursed. |
| ListAccountMovements | [AccountMovementsRequest](#pl-umamusume-tiket-api-v1-AccountMovementsRequest) | [AccountMovements](#pl-umamusume-tiket-api-v1-AccountMovements) | List Account Movements for authorized resources. |
| GetCashoutReceipt | [AccountQuery](#pl-umamusume-tiket-api-v1-AccountQuery) | [CashoutReceipt](#pl-umamusume-tiket-api-v1-CashoutReceipt) | Get Cashout Receipt for authorized resources. |

 



<a name="pl_umamusume_tiket_api_v1_administration-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## pl/umamusume/tiket/api/v1/administration.proto



<a name="pl-umamusume-tiket-api-v1-AddOpeningFundingRequest"></a>

### AddOpeningFundingRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| additional_paid | [int64](#int64) |  |  |
| additional_promotional | [int64](#int64) |  |  |
| expected_account_version | [int64](#int64) |  |  |
| idempotency_key | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-CreateRaceDayRequest"></a>

### CreateRaceDayRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| definition | [RaceDayConfiguration](#pl-umamusume-tiket-api-v1-RaceDayConfiguration) |  |  |
| idempotency_key | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-DayAcknowledgement"></a>

### DayAcknowledgement



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| phase | [DayPhase](#pl-umamusume-tiket-api-v1-DayPhase) |  |  |
| version | [int64](#int64) |  |  |






<a name="pl-umamusume-tiket-api-v1-DayCommand"></a>

### DayCommand



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| expected_version | [int64](#int64) |  |  |
| idempotency_key | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-DayQuery"></a>

### DayQuery



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-EnrollPlayerRequest"></a>

### EnrollPlayerRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| opening_paid | [int64](#int64) |  |  |
| opening_promotional | [int64](#int64) |  |  |
| expected_day_version | [int64](#int64) |  |  |
| idempotency_key | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-EnrollmentInformation"></a>

### EnrollmentInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| opening_paid | [int64](#int64) |  |  |
| opening_promotional | [int64](#int64) |  |  |
| balance | [int64](#int64) |  |  |
| account_version | [int64](#int64) |  |  |
| opening_version | [int64](#int64) |  |  |






<a name="pl-umamusume-tiket-api-v1-EnrollmentQuery"></a>

### EnrollmentQuery



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-OfficialResult"></a>

### OfficialResult



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |
| revision | [string](#string) |  |  |
| finisher_ids | [string](#string) | repeated |  |
| kind | [OfficialResultKind](#pl-umamusume-tiket-api-v1-OfficialResultKind) |  |  |
| status | [OfficialResultStatus](#pl-umamusume-tiket-api-v1-OfficialResultStatus) |  |  |






<a name="pl-umamusume-tiket-api-v1-PlayerFinancialSummary"></a>

### PlayerFinancialSummary



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| player_id | [string](#string) |  |  |
| amount | [int64](#int64) |  |  |






<a name="pl-umamusume-tiket-api-v1-PlayerList"></a>

### PlayerList



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| players | [EnrollmentInformation](#pl-umamusume-tiket-api-v1-EnrollmentInformation) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-PoolConfiguration"></a>

### PoolConfiguration



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| type | [PoolType](#pl-umamusume-tiket-api-v1-PoolType) |  |  |
| unit_stake | [int64](#int64) |  |  |
| take_basis_points | [int32](#int32) |  |  |






<a name="pl-umamusume-tiket-api-v1-PoolRefundSummary"></a>

### PoolRefundSummary



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| type | [PoolType](#pl-umamusume-tiket-api-v1-PoolType) |  |  |
| refunded_stake | [int64](#int64) |  |  |
| refunded_units | [int64](#int64) |  |  |






<a name="pl-umamusume-tiket-api-v1-PoolSettlementSummary"></a>

### PoolSettlementSummary



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| type | [PoolType](#pl-umamusume-tiket-api-v1-PoolType) |  |  |
| valid_stake | [int64](#int64) |  |  |
| winning_units | [int64](#int64) |  |  |
| distributable | [int64](#int64) |  |  |
| dividend_per_unit | [int64](#int64) |  |  |
| distributed | [int64](#int64) |  |  |
| house_allocation | [int64](#int64) |  |  |
| pool_version | [int64](#int64) |  |  |






<a name="pl-umamusume-tiket-api-v1-RaceAcknowledgement"></a>

### RaceAcknowledgement



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |
| phase | [RacePhase](#pl-umamusume-tiket-api-v1-RacePhase) |  |  |
| version | [int64](#int64) |  |  |
| scratched_horse_ids | [string](#string) | repeated |  |
| selected_result_revision | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-RaceCommand"></a>

### RaceCommand



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |
| expected_day_version | [int64](#int64) |  |  |
| expected_race_version | [int64](#int64) |  |  |
| idempotency_key | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-RaceConfiguration"></a>

### RaceConfiguration



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_id | [string](#string) |  |  |
| number | [int32](#int32) |  |  |
| racecourse_name | [string](#string) |  |  |
| title | [string](#string) |  |  |
| runners | [Runner](#pl-umamusume-tiket-api-v1-Runner) | repeated |  |
| pools | [PoolConfiguration](#pl-umamusume-tiket-api-v1-PoolConfiguration) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-RaceDayConfiguration"></a>

### RaceDayConfiguration



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| event_date | [string](#string) |  |  |
| display_number | [int32](#int32) |  |  |
| currency | [string](#string) |  |  |
| time_zone | [string](#string) |  |  |
| rules_version | [string](#string) |  |  |
| maximum_coupon_legs | [int32](#int32) |  |  |
| maximum_open_races | [int32](#int32) |  |  |
| no_winner_policy | [NoWinnerPolicy](#pl-umamusume-tiket-api-v1-NoWinnerPolicy) |  |  |
| races | [RaceConfiguration](#pl-umamusume-tiket-api-v1-RaceConfiguration) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-RaceDayInformation"></a>

### RaceDayInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| definition | [RaceDayConfiguration](#pl-umamusume-tiket-api-v1-RaceDayConfiguration) |  |  |
| status | [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement) |  |  |






<a name="pl-umamusume-tiket-api-v1-RaceInformation"></a>

### RaceInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| definition | [RaceConfiguration](#pl-umamusume-tiket-api-v1-RaceConfiguration) |  |  |
| status | [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement) |  |  |






<a name="pl-umamusume-tiket-api-v1-RaceLegResolutions"></a>

### RaceLegResolutions



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| resolutions | [TerminalLegResolution](#pl-umamusume-tiket-api-v1-TerminalLegResolution) | repeated |  |
| next_offset | [int32](#int32) | optional |  |






<a name="pl-umamusume-tiket-api-v1-RaceLegResolutionsRequest"></a>

### RaceLegResolutionsRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race | [RaceQuery](#pl-umamusume-tiket-api-v1-RaceQuery) |  |  |
| page_size | [int32](#int32) |  |  |
| offset | [int32](#int32) |  |  |






<a name="pl-umamusume-tiket-api-v1-RaceList"></a>

### RaceList



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| races | [RaceInformation](#pl-umamusume-tiket-api-v1-RaceInformation) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-RaceQuery"></a>

### RaceQuery



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-RefundAcknowledgement"></a>

### RefundAcknowledgement



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| status | [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement) |  |  |
| refunded_legs | [int64](#int64) |  |  |
| refunded_stake | [int64](#int64) |  |  |
| players | [PlayerFinancialSummary](#pl-umamusume-tiket-api-v1-PlayerFinancialSummary) | repeated |  |
| pools | [PoolRefundSummary](#pl-umamusume-tiket-api-v1-PoolRefundSummary) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-ResultAcknowledgement"></a>

### ResultAcknowledgement



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| status | [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement) |  |  |
| result | [OfficialResult](#pl-umamusume-tiket-api-v1-OfficialResult) |  |  |






<a name="pl-umamusume-tiket-api-v1-ResultRevisions"></a>

### ResultRevisions



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| results | [OfficialResult](#pl-umamusume-tiket-api-v1-OfficialResult) | repeated |  |
| next_offset | [int32](#int32) | optional |  |






<a name="pl-umamusume-tiket-api-v1-ResultRevisionsRequest"></a>

### ResultRevisionsRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race | [RaceQuery](#pl-umamusume-tiket-api-v1-RaceQuery) |  |  |
| page_size | [int32](#int32) |  |  |
| offset | [int32](#int32) |  |  |






<a name="pl-umamusume-tiket-api-v1-Runner"></a>

### Runner



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| horse_id | [string](#string) |  |  |
| program_number | [string](#string) |  |  |
| display_name | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-ScratchHorseRequest"></a>

### ScratchHorseRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| command | [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand) |  |  |
| horse_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-SettleRaceRequest"></a>

### SettleRaceRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| command | [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand) |  |  |
| settlement_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-SettlementInformation"></a>

### SettlementInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| settlement_id | [string](#string) |  |  |
| status | [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement) |  |  |
| result_revision | [string](#string) |  |  |
| pools | [PoolSettlementSummary](#pl-umamusume-tiket-api-v1-PoolSettlementSummary) | repeated |  |
| players | [PlayerFinancialSummary](#pl-umamusume-tiket-api-v1-PlayerFinancialSummary) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-SettlementQuery"></a>

### SettlementQuery



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |
| settlement_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-SubmitResultRequest"></a>

### SubmitResultRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| command | [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand) |  |  |
| revision | [string](#string) |  |  |
| finisher_ids | [string](#string) | repeated |  |
| kind | [OfficialResultKind](#pl-umamusume-tiket-api-v1-OfficialResultKind) |  |  |






<a name="pl-umamusume-tiket-api-v1-TerminalLegResolution"></a>

### TerminalLegResolution



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| coupon_id | [string](#string) |  |  |
| ordinal | [int32](#int32) |  |  |
| outcome | [TerminalLegOutcome](#pl-umamusume-tiket-api-v1-TerminalLegOutcome) |  |  |
| amount | [int64](#int64) |  |  |
| settlement_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-VerifyResultRequest"></a>

### VerifyResultRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| command | [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand) |  |  |
| revision | [string](#string) |  |  |





 


<a name="pl-umamusume-tiket-api-v1-DayPhase"></a>

### DayPhase


| Name | Number | Description |
| ---- | ------ | ----------- |
| DAY_PHASE_UNSPECIFIED | 0 |  |
| DRAFT | 1 |  |
| READY | 2 |  |
| RUNNING | 3 |  |
| FINALIZING | 4 |  |
| COMPLETED | 5 |  |
| CLOSED | 6 |  |



<a name="pl-umamusume-tiket-api-v1-NoWinnerPolicy"></a>

### NoWinnerPolicy


| Name | Number | Description |
| ---- | ------ | ----------- |
| NO_WINNER_POLICY_UNSPECIFIED | 0 |  |
| HOUSE_RETAINS_POOL | 1 |  |



<a name="pl-umamusume-tiket-api-v1-OfficialResultKind"></a>

### OfficialResultKind


| Name | Number | Description |
| ---- | ------ | ----------- |
| OFFICIAL_RESULT_KIND_UNSPECIFIED | 0 |  |
| NORMAL | 1 |  |
| EXCEPTIONAL | 2 |  |



<a name="pl-umamusume-tiket-api-v1-OfficialResultStatus"></a>

### OfficialResultStatus


| Name | Number | Description |
| ---- | ------ | ----------- |
| OFFICIAL_RESULT_STATUS_UNSPECIFIED | 0 |  |
| PROPOSED | 1 |  |
| VERIFIED | 2 |  |



<a name="pl-umamusume-tiket-api-v1-PoolType"></a>

### PoolType


| Name | Number | Description |
| ---- | ------ | ----------- |
| POOL_TYPE_UNSPECIFIED | 0 |  |
| WIN | 1 |  |
| QUINELLA | 2 |  |
| EXACTA | 3 |  |
| TRIFECTA | 4 |  |



<a name="pl-umamusume-tiket-api-v1-RacePhase"></a>

### RacePhase


| Name | Number | Description |
| ---- | ------ | ----------- |
| RACE_PHASE_UNSPECIFIED | 0 |  |
| SCHEDULED | 1 |  |
| BETTING_OPEN | 2 |  |
| BETTING_CLOSED | 3 |  |
| RESULT_PROPOSED | 4 |  |
| RESULT_VERIFIED | 5 |  |
| SETTLED | 6 |  |
| VOIDED | 7 |  |



<a name="pl-umamusume-tiket-api-v1-TerminalLegOutcome"></a>

### TerminalLegOutcome


| Name | Number | Description |
| ---- | ------ | ----------- |
| TERMINAL_LEG_OUTCOME_UNSPECIFIED | 0 |  |
| REFUNDED | 1 |  |
| WON | 2 |  |
| LOST | 3 |  |


 

 


<a name="pl-umamusume-tiket-api-v1-EnrollmentService"></a>

### EnrollmentService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| EnrollPlayer | [EnrollPlayerRequest](#pl-umamusume-tiket-api-v1-EnrollPlayerRequest) | [EnrollmentInformation](#pl-umamusume-tiket-api-v1-EnrollmentInformation) | Enroll with opening funds before start. |
| AddOpeningFunding | [AddOpeningFundingRequest](#pl-umamusume-tiket-api-v1-AddOpeningFundingRequest) | [EnrollmentInformation](#pl-umamusume-tiket-api-v1-EnrollmentInformation) | Append opening funding before start and account activity. |
| GetEnrollment | [EnrollmentQuery](#pl-umamusume-tiket-api-v1-EnrollmentQuery) | [EnrollmentInformation](#pl-umamusume-tiket-api-v1-EnrollmentInformation) | Get Enrollment for authorized resources. |
| ListPlayers | [DayQuery](#pl-umamusume-tiket-api-v1-DayQuery) | [PlayerList](#pl-umamusume-tiket-api-v1-PlayerList) | List Players for authorized resources. |


<a name="pl-umamusume-tiket-api-v1-RaceDayService"></a>

### RaceDayService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| CreateRaceDay | [CreateRaceDayRequest](#pl-umamusume-tiket-api-v1-CreateRaceDayRequest) | [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement) | Create the complete immutable DRAFT definition. |
| GetRaceDay | [DayQuery](#pl-umamusume-tiket-api-v1-DayQuery) | [RaceDayInformation](#pl-umamusume-tiket-api-v1-RaceDayInformation) | Get Race Day for authorized resources. |
| PublishRaceDay | [DayCommand](#pl-umamusume-tiket-api-v1-DayCommand) | [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement) | Freeze definition and enter READY. |
| StartRaceDay | [DayCommand](#pl-umamusume-tiket-api-v1-DayCommand) | [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement) | Enter RUNNING, freezing enrollment and funding. |
| FinalizeRaceDay | [DayCommand](#pl-umamusume-tiket-api-v1-DayCommand) | [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement) | Enter FINALIZING once betting is closed. |
| CompleteRaceDay | [DayCommand](#pl-umamusume-tiket-api-v1-DayCommand) | [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement) | Prove terminal races/pools/legs and enter COMPLETED. |
| CloseRaceDay | [DayCommand](#pl-umamusume-tiket-api-v1-DayCommand) | [DayAcknowledgement](#pl-umamusume-tiket-api-v1-DayAcknowledgement) | Prove terminal cashouts and enter CLOSED. |


<a name="pl-umamusume-tiket-api-v1-RaceService"></a>

### RaceService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| ScratchHorse | [ScratchHorseRequest](#pl-umamusume-tiket-api-v1-ScratchHorseRequest) | [RefundAcknowledgement](#pl-umamusume-tiket-api-v1-RefundAcknowledgement) | Refund active legs containing the scratched horse before verification. |
| VoidRace | [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand) | [RefundAcknowledgement](#pl-umamusume-tiket-api-v1-RefundAcknowledgement) | Refund all remaining active legs before verification. |
| ListRaces | [DayQuery](#pl-umamusume-tiket-api-v1-DayQuery) | [RaceList](#pl-umamusume-tiket-api-v1-RaceList) | List Races for authorized resources. |
| GetRace | [RaceQuery](#pl-umamusume-tiket-api-v1-RaceQuery) | [RaceInformation](#pl-umamusume-tiket-api-v1-RaceInformation) | Get Race for authorized resources. |
| GetRaceStatus | [RaceQuery](#pl-umamusume-tiket-api-v1-RaceQuery) | [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement) | Get Race Status for authorized resources. |
| OpenBetting | [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand) | [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement) | Open a scheduled race within the event-wide limit. |
| CloseBetting | [RaceCommand](#pl-umamusume-tiket-api-v1-RaceCommand) | [RaceAcknowledgement](#pl-umamusume-tiket-api-v1-RaceAcknowledgement) | Close betting before proposals. |


<a name="pl-umamusume-tiket-api-v1-ResultService"></a>

### ResultService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| SubmitResult | [SubmitResultRequest](#pl-umamusume-tiket-api-v1-SubmitResultRequest) | [ResultAcknowledgement](#pl-umamusume-tiket-api-v1-ResultAcknowledgement) | Propose a normal ordered result after betting closes. |
| VerifyResult | [VerifyResultRequest](#pl-umamusume-tiket-api-v1-VerifyResultRequest) | [ResultAcknowledgement](#pl-umamusume-tiket-api-v1-ResultAcknowledgement) | Verify the selected normal proposal with three or more eligible finishers. |
| GetResult | [RaceQuery](#pl-umamusume-tiket-api-v1-RaceQuery) | [ResultAcknowledgement](#pl-umamusume-tiket-api-v1-ResultAcknowledgement) | Get Result for authorized resources. |
| ListResultRevisions | [ResultRevisionsRequest](#pl-umamusume-tiket-api-v1-ResultRevisionsRequest) | [ResultRevisions](#pl-umamusume-tiket-api-v1-ResultRevisions) | List Result Revisions for authorized resources. |
| SettleRace | [SettleRaceRequest](#pl-umamusume-tiket-api-v1-SettleRaceRequest) | [SettlementInformation](#pl-umamusume-tiket-api-v1-SettlementInformation) | Atomically settle four pools from a verified normal result. |
| GetSettlement | [SettlementQuery](#pl-umamusume-tiket-api-v1-SettlementQuery) | [SettlementInformation](#pl-umamusume-tiket-api-v1-SettlementInformation) | Get Settlement for authorized resources. |
| ListRaceLegResolutions | [RaceLegResolutionsRequest](#pl-umamusume-tiket-api-v1-RaceLegResolutionsRequest) | [RaceLegResolutions](#pl-umamusume-tiket-api-v1-RaceLegResolutions) | List Race Leg Resolutions for authorized resources. |

 



<a name="pl_umamusume_tiket_api_v1_betting-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## pl/umamusume/tiket/api/v1/betting.proto



<a name="pl-umamusume-tiket-api-v1-BettingSelection"></a>

### BettingSelection



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| positional | [PositionalSelection](#pl-umamusume-tiket-api-v1-PositionalSelection) |  |  |
| box | [BoxSelection](#pl-umamusume-tiket-api-v1-BoxSelection) |  |  |






<a name="pl-umamusume-tiket-api-v1-BoxSelection"></a>

### BoxSelection



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| horse_ids | [string](#string) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-CouponInformation"></a>

### CouponInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| coupon_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| pricing | [CouponPricing](#pl-umamusume-tiket-api-v1-CouponPricing) |  |  |
| purchased_at | [string](#string) |  |  |
| point_of_sale_number | [int32](#int32) |  |  |
| cashier_identifier | [string](#string) |  |  |
| legs | [PurchasedLeg](#pl-umamusume-tiket-api-v1-PurchasedLeg) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-CouponList"></a>

### CouponList



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| coupons | [CouponInformation](#pl-umamusume-tiket-api-v1-CouponInformation) | repeated |  |
| next_offset | [int32](#int32) | optional |  |






<a name="pl-umamusume-tiket-api-v1-CouponPricing"></a>

### CouponPricing



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |
| bet_type | [PoolType](#pl-umamusume-tiket-api-v1-PoolType) |  |  |
| selection | [BettingSelection](#pl-umamusume-tiket-api-v1-BettingSelection) |  |  |
| combination_count | [int32](#int32) |  |  |
| units_per_combination | [int64](#int64) |  |  |
| total_units | [int64](#int64) |  |  |
| unit_stake | [int64](#int64) |  |  |
| total_stake | [int64](#int64) |  |  |
| currency | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-CouponQuery"></a>

### CouponQuery



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| coupon_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-ListPlayerCouponsRequest"></a>

### ListPlayerCouponsRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| page_size | [int32](#int32) |  |  |
| offset | [int32](#int32) |  |  |






<a name="pl-umamusume-tiket-api-v1-Position"></a>

### Position



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| horse_id | [string](#string) |  |  |
| wall | [bool](#bool) |  |  |






<a name="pl-umamusume-tiket-api-v1-PositionalSelection"></a>

### PositionalSelection



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| positions | [Position](#pl-umamusume-tiket-api-v1-Position) | repeated |  |






<a name="pl-umamusume-tiket-api-v1-PrintAttemptInformation"></a>

### PrintAttemptInformation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| print_attempt_id | [string](#string) |  |  |
| started_at | [string](#string) |  |  |
| completed_at | [string](#string) |  |  |
| result | [string](#string) |  |  |
| error | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-PrintStatus"></a>

### PrintStatus



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| print_job_id | [string](#string) |  |  |
| coupon_id | [string](#string) |  |  |
| type | [PrintJobType](#pl-umamusume-tiket-api-v1-PrintJobType) |  |  |
| status | [DispatchStatus](#pl-umamusume-tiket-api-v1-DispatchStatus) |  |  |
| attempt_count | [int32](#int32) |  |  |
| latest_attempt | [PrintAttemptInformation](#pl-umamusume-tiket-api-v1-PrintAttemptInformation) |  |  |
| last_error | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-PrintStatusQuery"></a>

### PrintStatusQuery



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| print_job_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-PurchaseCouponRequest"></a>

### PurchaseCouponRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| coupon_id | [string](#string) |  |  |
| bet_type | [PoolType](#pl-umamusume-tiket-api-v1-PoolType) |  |  |
| selection | [BettingSelection](#pl-umamusume-tiket-api-v1-BettingSelection) |  |  |
| units_per_combination | [int64](#int64) |  |  |
| quote_confirmation | [string](#string) |  |  |
| expected_day_version | [int64](#int64) |  |  |
| expected_race_version | [int64](#int64) |  |  |
| expected_account_version | [int64](#int64) | optional |  |
| point_of_sale_number | [int32](#int32) |  |  |
| cashier_identifier | [string](#string) |  |  |
| idempotency_key | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-PurchaseCouponResponse"></a>

### PurchaseCouponResponse



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| coupon_id | [string](#string) |  |  |
| player_id | [string](#string) |  |  |
| pricing | [CouponPricing](#pl-umamusume-tiket-api-v1-CouponPricing) |  |  |
| purchased_at | [string](#string) |  |  |
| account_version | [int64](#int64) |  |  |
| balance | [int64](#int64) |  |  |
| print_job_id | [string](#string) |  |  |
| printing_status | [DispatchStatus](#pl-umamusume-tiket-api-v1-DispatchStatus) |  |  |






<a name="pl-umamusume-tiket-api-v1-PurchasedLeg"></a>

### PurchasedLeg



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| ordinal | [int32](#int32) |  |  |
| horse_ids | [string](#string) | repeated |  |
| units | [int64](#int64) |  |  |
| stake | [int64](#int64) |  |  |
| coupon_id | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-QuoteCouponRequest"></a>

### QuoteCouponRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| race_id | [string](#string) |  |  |
| bet_type | [PoolType](#pl-umamusume-tiket-api-v1-PoolType) |  |  |
| selection | [BettingSelection](#pl-umamusume-tiket-api-v1-BettingSelection) |  |  |
| units_per_combination | [int64](#int64) |  |  |






<a name="pl-umamusume-tiket-api-v1-QuoteCouponResponse"></a>

### QuoteCouponResponse



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| pricing | [CouponPricing](#pl-umamusume-tiket-api-v1-CouponPricing) |  |  |
| race_day_version | [int64](#int64) |  |  |
| race_version | [int64](#int64) |  |  |
| confirmation | [string](#string) |  |  |






<a name="pl-umamusume-tiket-api-v1-ReprintCouponRequest"></a>

### ReprintCouponRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| race_day_id | [string](#string) |  |  |
| coupon_id | [string](#string) |  |  |
| idempotency_key | [string](#string) |  |  |





 


<a name="pl-umamusume-tiket-api-v1-DispatchStatus"></a>

### DispatchStatus


| Name | Number | Description |
| ---- | ------ | ----------- |
| DISPATCH_STATUS_UNSPECIFIED | 0 |  |
| PENDING | 1 |  |
| CLAIMED | 2 |  |
| RETRYING | 3 |  |
| DISPATCHED | 4 |  |
| PERMANENTLY_FAILED | 5 |  |



<a name="pl-umamusume-tiket-api-v1-PrintJobType"></a>

### PrintJobType


| Name | Number | Description |
| ---- | ------ | ----------- |
| PRINT_JOB_TYPE_UNSPECIFIED | 0 |  |
| ORIGINAL | 1 |  |
| REPRINT | 2 |  |


 

 


<a name="pl-umamusume-tiket-api-v1-BettingService"></a>

### BettingService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| QuoteCoupon | [QuoteCouponRequest](#pl-umamusume-tiket-api-v1-QuoteCouponRequest) | [QuoteCouponResponse](#pl-umamusume-tiket-api-v1-QuoteCouponResponse) | Quote pricing and canonical expansion without reserving funds. |
| PurchaseCoupon | [PurchaseCouponRequest](#pl-umamusume-tiket-api-v1-PurchaseCouponRequest) | [PurchaseCouponResponse](#pl-umamusume-tiket-api-v1-PurchaseCouponResponse) | Revalidate confirmation/versions and atomically buy all legs and enqueue printing. |
| GetCoupon | [CouponQuery](#pl-umamusume-tiket-api-v1-CouponQuery) | [CouponInformation](#pl-umamusume-tiket-api-v1-CouponInformation) | Get Coupon for authorized resources. |
| ListPlayerCoupons | [ListPlayerCouponsRequest](#pl-umamusume-tiket-api-v1-ListPlayerCouponsRequest) | [CouponList](#pl-umamusume-tiket-api-v1-CouponList) | List Player Coupons for authorized resources. |


<a name="pl-umamusume-tiket-api-v1-PrintingService"></a>

### PrintingService
Authenticated cashier operations; event, player and POS grants are checked.

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| GetPrintStatus | [PrintStatusQuery](#pl-umamusume-tiket-api-v1-PrintStatusQuery) | [PrintStatus](#pl-umamusume-tiket-api-v1-PrintStatus) | Read current dispatch state; no physical acknowledgement is implied. |
| ReprintCoupon | [ReprintCouponRequest](#pl-umamusume-tiket-api-v1-ReprintCouponRequest) | [PrintStatus](#pl-umamusume-tiket-api-v1-PrintStatus) | Enqueue a separate REPRINT job without financial effects. |

 



<a name="pl_umamusume_tiket_api_v1_system-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## pl/umamusume/tiket/api/v1/system.proto



<a name="pl-umamusume-tiket-api-v1-PingRequest"></a>

### PingRequest







<a name="pl-umamusume-tiket-api-v1-PingResponse"></a>

### PingResponse



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| status | [string](#string) |  |  |
| api_version | [string](#string) |  |  |





 

 

 


<a name="pl-umamusume-tiket-api-v1-SystemService"></a>

### SystemService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#pl-umamusume-tiket-api-v1-PingRequest) | [PingResponse](#pl-umamusume-tiket-api-v1-PingResponse) | Public process/API ping without domain or database side effects. |

 



## Scalar Value Types

| .proto Type | Notes | C++ | Java | Python | Go | C# | PHP | Ruby |
| ----------- | ----- | --- | ---- | ------ | -- | -- | --- | ---- |
| <a name="double" /> double |  | double | double | float | float64 | double | float | Float |
| <a name="float" /> float |  | float | float | float | float32 | float | float | Float |
| <a name="int32" /> int32 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint32 instead. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="int64" /> int64 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint64 instead. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="uint32" /> uint32 | Uses variable-length encoding. | uint32 | int | int/long | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="uint64" /> uint64 | Uses variable-length encoding. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum or Fixnum (as required) |
| <a name="sint32" /> sint32 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int32s. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sint64" /> sint64 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int64s. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="fixed32" /> fixed32 | Always four bytes. More efficient than uint32 if values are often greater than 2^28. | uint32 | int | int | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="fixed64" /> fixed64 | Always eight bytes. More efficient than uint64 if values are often greater than 2^56. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum |
| <a name="sfixed32" /> sfixed32 | Always four bytes. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sfixed64" /> sfixed64 | Always eight bytes. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="bool" /> bool |  | bool | boolean | boolean | bool | bool | boolean | TrueClass/FalseClass |
| <a name="string" /> string | A string must always contain UTF-8 encoded or 7-bit ASCII text. | string | String | str/unicode | string | string | string | String (UTF-8) |
| <a name="bytes" /> bytes | May contain any arbitrary sequence of bytes. | string | ByteString | str | []byte | ByteString | string | String (ASCII-8BIT) |

