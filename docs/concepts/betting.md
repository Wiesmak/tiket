# Betting rules

WIN selects first; QUINELLA selects the first two in either order; EXACTA selects the ordered first two;
TRIFECTA selects the ordered first three. Atomic horses must be distinct.
Quinella sorts stable UUID string representations, giving order-independent equality and hashing.
Expansions are sorted by canonical type/horse representations; input collection iteration order never affects legs.

Use CouponSelection.Positional with Horse and Wall position choices, or CouponSelection.Box with horse IDs.
No ticket string parser is required. Box and wall cannot be mixed.

A wall ranges over currently eligible roster IDs, excluding horses already occupying another position.
At least one position must be explicit. Repeated explicit IDs, unknown IDs and scratched explicit IDs are rejected.
For N eligible horses, an Exacta wall has N−1 legs. Trifecta with one fixed horse has
(N−1)(N−2); with two fixed horses it has N−2. Reversed Quinella walls expand identically.

A box has distinct explicit IDs, at least two (three for Trifecta).
For N box horses: Win N; Quinella N(N−1)/2; Exacta N(N−1); Trifecta N(N−1)(N−2).
Win boxes are supported. Duplicate inputs are rejected before canonicalization.
BigInteger count calculations reject oversized expansions before enumerating combinations.
The configurable default maximum is 10,000 legs.

Each of K combinations receives Q positive units at positive unit stake S.
Total units = KQ; total stake = KQS; each leg stake = QS. All Long arithmetic is checked.
An Exacta box of three horses with Q=2 and S=10 costs 120, with six legs of 20.

Expansion freezes at purchase. A later scratch refunds every active leg containing that horse,
leaving other legs and historical coupons intact. A void refunds all remaining active race legs.
Refunds exclude legs from pools and reverse qualifying turnover. Already refunded legs are skipped.
Verified or settled races require exceptional administrative correction, never automatic reversal.

Ordinary verification requires at least three distinct valid finishers, sufficient for all four pools.
A provisional nonempty result may be incomplete and corrected before verification.
Unknown, repeated or scratched finishers are invalid. Exceptional outcomes such as dead heats are
explicitly unsupported; cancel/void or use a future administrative policy.
