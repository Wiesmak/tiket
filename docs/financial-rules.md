# Financial rules

Money is a nonnegative Long in integer currency units. Units and unit stakes are positive.
TakeRate is 0–10,000 basis points. BigInteger intermediates prevent temporary multiplication overflow.
Results exceeding Long capacity are rejected; there is no floating point or approximate arithmetic.

## Independent pools

Every race/type has its own PoolKey and frozen PoolRules.
For valid pool stake P, house basis points h and winning units W:

* D = floor(P × (10000 − h) / 10000)
* d = floor(D / W) when W > 0, otherwise 0
* Leg payout = leg units × d for winning legs
* Distributed = W × d
* House allocation = P − Distributed

The return includes the winning stake; no separate stake refund occurs.
The house receives take and all division remainder.
At 0% the whole pool is distributable before division; at 100% winners receive zero.
When W=0 the house retains the whole pool. Empty pools produce no payouts.
D smaller than W produces zero dividends even for winners.

Example: P=1001, h=1500, W=3 → D=850, d=283, distributed=849, house=152.

```kotlin
val settlement = PoolSettlementCalculator.calculate(
    poolKey, Money(1001), 3, TakeRate(1500)
)
```

The aggregate calculator supports reconciled totals. For production settlement use the snapshot/rules/winner
overload or RaceSettlementPlanner, which verifies stake/unit consistency and all supplied leg data.

## Refunds and conservation

Refund exactly a leg's original stake once. Pool units and stake are reduced; turnover is reduced by the refund.
Active legs settle; refunded legs never win or lose again.
For each pool: valid stake = player payouts + house allocation.
For historical accounting: original accepted stake = refunds + payouts + house allocation.
FinancialReconciliation validates this identity with checked arithmetic.

## One account and promotional eligibility

Opening balance = opening paid + opening freebet. There are no separate wallets or funding attribution.
Balance = opening balance − accepted stakes + refunds + credited winnings before final cashout.
Qualifying turnover S = accepted stakes − refunds.

With opening paid C, opening freebet F, credited winnings W and balance B:
U = min(F,S); withdrawable = min(B,C+U+W); forfeitable = B−withdrawable.
The eligibility limit uses BigInteger even when C+U+W temporarily exceeds Long.
Winnings are fully eligible. Lost stakes still unlock promotional eligibility; refunded stakes do not.

Examples (paid/freebet, net stakes, winnings → balance; withdrawal; forfeiture):

* 0/100, 0, 0 → 100; 0; 100
* 0/100, 30, 0 → 70; 30; 40
* 0/100, 30, 150 → 220; 180; 40
* 100/100, 0, 0 → 200; 100; 100
* 0/100, 60 accepted and 20 refunded, 0 → 60; 40; 20

WithdrawalPolicy.hypothetical can compute eligibility at any stage.
WithdrawalPolicy.quote and AccountProjector.cashOut require COMPLETED.
Cashout records distinct final withdrawal and promotional forfeiture movements and leaves zero balance.
No further ordinary movements are accepted after cashout.
