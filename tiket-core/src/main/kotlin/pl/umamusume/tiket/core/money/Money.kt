package pl.umamusume.tiket.core.money
import java.math.BigInteger
import pl.umamusume.tiket.core.validation.*

@JvmInline value class Money(val amount: Long) : Comparable<Money> {
    init { ensure(amount >= 0, ErrorCode.INVALID_STAKE, "negative money: $amount") }
    operator fun plus(other: Money) = Money(Math.addExact(amount, other.amount))
    operator fun minus(other: Money): Money {
        ensure(amount >= other.amount, ErrorCode.INSUFFICIENT_BALANCE, "money underflow")
        return Money(amount - other.amount)
    }
    operator fun times(units: Long) = Money(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(units)).longValueExact())
    override fun compareTo(other: Money) = amount.compareTo(other.amount)
    companion object { val ZERO = Money(0) }
}
@JvmInline value class BetUnits(val value: Long) {
    init { ensure(value > 0, ErrorCode.INVALID_UNITS, "units must be positive") }
}
@JvmInline value class TakeRate(val basisPoints: Int) {
    init { ensure(basisPoints in 0..10000, ErrorCode.INVALID_TAKE_RATE, "basis points outside 0..10000") }
}
internal fun moneySum(values: Iterable<Money>): Money = values.fold(Money.ZERO, Money::plus)
