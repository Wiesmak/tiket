package consumer

import pl.umamusume.tiket.core.accounting.AccountProjection
import pl.umamusume.tiket.core.accounting.AccountSnapshot
import pl.umamusume.tiket.core.coupon.Coupon
import pl.umamusume.tiket.core.coupon.CouponLeg
import pl.umamusume.tiket.core.validation.getOrThrow

// This source set is compiled as an independent Kotlin module against the published JAR shape.
// No internal constructors, test fixtures, or friend paths are available.
fun restoreCoupon(original: Coupon): Coupon {
    val legs = original.legs.map {
        CouponLeg.reconstitute(it.id, it.bet, it.units, original.unitStake, it.stake).getOrThrow()
    }
    return Coupon.reconstitute(
        original.id, original.raceDayId, original.raceId, original.playerId, original.betType,
        original.selection, original.unitsPerCombination, original.unitStake, original.totalStake,
        legs, original.purchasedAt
    ).getOrThrow()
}

fun restoreAccount(original: AccountSnapshot, openingVersion: Long = 0): AccountSnapshot =
    AccountSnapshot.reconstitute(
        original.entry, original.movements, original.version,
        expectedProjection = AccountProjection.from(original), openingVersion = openingVersion
    ).getOrThrow()
