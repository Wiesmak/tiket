package pl.umamusume.tiket.core.identity
import java.util.UUID
import pl.umamusume.tiket.core.betting.BetType
@JvmInline value class RaceDayId(val value: UUID)
@JvmInline value class RaceId(val value: UUID)
@JvmInline value class HorseId(val value: UUID)
@JvmInline value class PlayerId(val value: UUID)
@JvmInline value class CouponId(val value: UUID)
@JvmInline value class SettlementId(val value: UUID)

data class BetLegId(val couponId: CouponId, val ordinal: Int) { init { require(ordinal >= 0) } }
data class PoolKey(val raceId: RaceId, val betType: BetType)
internal val horseOrder: Comparator<HorseId> = compareBy { it.value.toString() }
