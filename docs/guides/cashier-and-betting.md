# Cashier workflow
Authenticate as CASHIER with explicit event, player and POS grants issued by the identity provider. The request cashier_identifier must match tiket_cashier; a POS number supplied in JSON is not authorization.

Before start, EnrollPlayer creates opening paid/promotional funds and AddOpeningFunding appends additional funding. Funding is frozen when the day enters RUNNING. There are no in-event deposits.

When an administrator opens betting, obtain the customer's structured selection, pool and units per combination. Use horse UUIDs from GetRace, displaying program numbers to the customer. QuoteCoupon returns canonical pricing, total combinations/units/stake, versions and confirmation. Present these terms for approval. An Exacta #2,3,4 with 2 units per combination at stake 10 costs 120, not 20.

Persist the intended PurchaseCoupon request and its key before transmission. Include quote confirmation, expected day/race versions, optional account version, new coupon UUID and authenticated cashier/POS metadata. Purchase atomically stores all legs and debits the single balance. A failed print never cancels the wager.

Record the returned coupon and print job IDs. GetPrintStatus reports current dispatch; the original purchase acknowledgement deliberately preserves its initial status on replay. ReprintCoupon is an explicit separate print operation with its own key. Reconcile uncertain physical output before reprinting.

Use [worked fixtures](../api/examples.md) for actual schemas. On stale state, refresh and quote again; on a timeout, replay the identical intended purchase before issuing another. Never use a new coupon/key merely because the response was lost. Closing betting prevents further purchases, even if a client obtained an earlier quote.

After the day is COMPLETED, follow [cashout](cashout.md). A recorded receipt is not evidence of physical disbursement: the cashier's operating procedure must reconcile delivery of money separately.
