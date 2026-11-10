package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.betting.CouponSelection
import pl.umamusume.tiket.core.betting.PositionChoice
import pl.umamusume.tiket.core.coupon.Coupon
import pl.umamusume.tiket.core.coupon.CouponLeg
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.BetUnits
import pl.umamusume.tiket.core.validation.getOrThrow
import java.util.*

data class PurchaseMetadata(val posNumber: Int, val cashierId: String) {
    init {
        require(posNumber > 0 && cashierId.isNotBlank())
    }
}

data class StoredCoupon(val coupon: Coupon, val metadata: PurchaseMetadata, val purchaseEligibility: Set<HorseId>)

@Repository
class CouponRepository(
    private val jdbc: JdbcTemplate,
    private val tx: PersistenceTransactions,
    private val races: RaceRepository
) {
    fun insert(c: Coupon, metadata: PurchaseMetadata, purchaseEligibility: Set<HorseId>) = tx.required {
        val race = races.load(c.raceDayId, c.raceId) ?: throw InvalidPersistenceException("Foreign race")
        checked(race.roster.containsAll(purchaseEligibility), "Invalid historical eligibility")
        Coupon.reconstitute(
            c.id,
            c.raceDayId,
            c.raceId,
            c.playerId,
            c.betType,
            c.selection,
            c.unitsPerCombination,
            c.unitStake,
            c.totalStake,
            c.legs,
            c.purchasedAt,
            purchaseEligibility
        ).getOrThrow()
        jdbc.update(
            "INSERT INTO coupon VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            c.id.value,
            c.raceDayId.value,
            c.raceId.value,
            c.playerId.value,
            c.betType.name,
            if (c.selection is CouponSelection.Box) "BOX" else "POSITIONAL",
            c.unitsPerCombination.value,
            c.unitStake.amount,
            c.totalStake.amount,
            timestamp(c.purchasedAt),
            metadata.posNumber,
            metadata.cashierId
        )
        when (val s = c.selection) {
            is CouponSelection.Box -> s.horses.forEachIndexed { i, h ->
                jdbc.update(
                    "INSERT INTO coupon_selection VALUES (?,?,'BOX',?)",
                    c.id.value,
                    i,
                    h.value
                )
            }

            is CouponSelection.Positional -> s.positions.forEachIndexed { i, h ->
                jdbc.update(
                    "INSERT INTO coupon_selection VALUES (?,?,?,?)",
                    c.id.value,
                    i,
                    if (h is PositionChoice.Horse) "HORSE" else "WALL",
                    (h as? PositionChoice.Horse)?.id?.value
                )
            }
        }
        purchaseEligibility.forEach { jdbc.update("INSERT INTO coupon_eligibility VALUES (?,?)", c.id.value, it.value) }
        jdbc.batchUpdate(
            "INSERT INTO coupon_leg VALUES (?,?,?,?,?,?,?,?,?,?)",
            c.legs.map { l ->
                arrayOf(
                    c.id.value,
                    l.id.ordinal,
                    c.raceId.value,
                    c.betType.name,
                    *atomArgs(l.bet),
                    l.units.value,
                    l.stake.amount
                )
            })
        Unit
    }

    fun load(day: RaceDayId, id: CouponId): StoredCoupon? =
        jdbc.one("SELECT * FROM coupon WHERE race_day_id=? AND id=?", day.value, id.value) { r ->
            val type = BetType.valueOf(r.getString("bet_type"))
            val choices = jdbc.query("SELECT * FROM coupon_selection WHERE coupon_id=? ORDER BY ordinal", { s, i ->
                checked(s.getInt("ordinal") == i, "Selection order gap")
                s.getString("choice") to s.getObject("horse_id", UUID::class.java)?.let(::HorseId)
            }, id.value)
            val selection = when (r.getString("selection_kind")) {
                "BOX" -> {
                    checked(
                        choices.all { it.first == "BOX" && it.second != null },
                        "Invalid box choices"
                    ); CouponSelection.Box(choices.map { it.second!! })
                }

                "POSITIONAL" -> CouponSelection.Positional(choices.map { (kind, horse) ->
                    when (kind) {
                        "HORSE" -> PositionChoice.Horse(
                            horse ?: throw InvalidPersistenceException("Missing selection horse")
                        )

                        "WALL" -> {
                            checked(horse == null, "Wall with horse"); PositionChoice.Wall
                        }

                        else -> throw InvalidPersistenceException("Unknown position variant")
                    }
                })

                else -> throw InvalidPersistenceException("Unknown selection kind")
            }
            val stake = r.money("unit_stake")
            val legs = jdbc.query("SELECT * FROM coupon_leg WHERE coupon_id=? ORDER BY ordinal", { s, _ ->
                CouponLeg.reconstitute(
                    BetLegId(id, s.getInt("ordinal")),
                    s.atomic(),
                    BetUnits(s.getLong("units")),
                    stake,
                    s.money("stake")
                ).getOrThrow()
            }, id.value)
            val eligibility = jdbc.query(
                "SELECT horse_id FROM coupon_eligibility WHERE coupon_id=? ORDER BY horse_id",
                { s, _ -> HorseId(s.uuid("horse_id")) },
                id.value
            ).toSet()
            checked(eligibility.isNotEmpty(), "Missing historical eligibility")
            val coupon = Coupon.reconstitute(
                id,
                day,
                RaceId(r.uuid("race_id")),
                PlayerId(r.uuid("player_id")),
                type,
                selection,
                BetUnits(r.getLong("units")),
                stake,
                r.money("total_stake"),
                legs,
                r.getTimestamp("purchased_at").toInstant(),
                eligibility
            ).getOrThrow()
            StoredCoupon(
                coupon,
                PurchaseMetadata(r.getInt("pos_number"), r.getString("cashier_id")),
                java.util.Set.copyOf(eligibility)
            )
        }

    fun list(day: RaceDayId, race: RaceId): List<StoredCoupon> = jdbc.query(
        "SELECT id FROM coupon WHERE race_day_id=? AND race_id=? ORDER BY purchased_at,id",
        { r, _ -> CouponId(r.uuid("id")) },
        day.value,
        race.value
    ).map { load(day, it)!! }
}
