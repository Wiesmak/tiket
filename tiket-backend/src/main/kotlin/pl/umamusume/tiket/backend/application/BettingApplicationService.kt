package pl.umamusume.tiket.backend.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.accounting.AccountProjector
import pl.umamusume.tiket.core.betting.BetPlacementPolicy
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.betting.CouponSelection
import pl.umamusume.tiket.core.betting.PositionChoice
import pl.umamusume.tiket.core.coupon.CouponPricingCalculator
import pl.umamusume.tiket.core.coupon.CouponQuote
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.race.RacePhase
import pl.umamusume.tiket.core.raceday.RaceDayPhase
import pl.umamusume.tiket.core.validation.getOrThrow
import java.security.MessageDigest
import java.time.Instant

fun BettingSelection.domain(): CouponSelection = when (variantCase) {
    BettingSelection.VariantCase.BOX -> CouponSelection.Box(box.horseIdsList.map { HorseId(uuid(it)) })
    BettingSelection.VariantCase.POSITIONAL -> CouponSelection.Positional(positional.positionsList.map {
        when (it.choiceCase) {
            Position.ChoiceCase.HORSE_ID -> PositionChoice.Horse(HorseId(uuid(it.horseId)))
            Position.ChoiceCase.WALL -> {
                require(it.wall); PositionChoice.Wall
            }

            else -> throw IllegalArgumentException("Position choice required")
        }
    })

    else -> throw IllegalArgumentException("Selection required")
}

fun CouponSelection.proto(): BettingSelection = BettingSelection.newBuilder().apply {
    when (val s = this@proto) {
        is CouponSelection.Box -> setBox(BoxSelection.newBuilder().addAllHorseIds(s.horses.map { it.value.toString() }))
        is CouponSelection.Positional -> setPositional(
            PositionalSelection.newBuilder().addAllPositions(s.positions.map {
                Position.newBuilder().apply {
                    when (it) {
                        is PositionChoice.Horse -> horseId = it.id.value.toString()
                        PositionChoice.Wall -> wall = true
                    }
                }.build()
            })
        )
    }
}.build()

fun bettingType(type: PoolType): BetType {
    require(type in listOf(PoolType.WIN, PoolType.QUINELLA, PoolType.EXACTA, PoolType.TRIFECTA))
    return BetType.valueOf(type.name)
}

private fun pricing(day: RaceDayId, q: CouponQuote, currency: String): CouponPricing =
    CouponPricing.newBuilder().setRaceDayId(day.value.toString()).setRaceId(q.raceId.value.toString())
        .setBetType(PoolType.valueOf(q.betType.name)).setSelection(q.selection.proto())
        .setCombinationCount(q.combinations).setUnitsPerCombination(q.unitsPerCombination.value)
        .setTotalUnits(q.totalUnits).setUnitStake(q.unitStake.amount).setTotalStake(q.totalStake.amount)
        .setCurrency(currency).build()

private fun confirmation(q: CouponQuote, pricing: CouponPricing, dv: Long, rv: Long): String {
    // Length-delimited protobuf pricing + fixed width versions + ordered canonical outcomes.
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(pricing.toByteArray())
    digest.update(java.nio.ByteBuffer.allocate(16).putLong(dv).putLong(rv).array())
    q.expansion.bets.forEach { digest.update(it.canonical.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Service
class BettingApplicationService(
    private val days: RaceDayApplicationService, private val races: RaceRepository,
    private val accounts: AccountRepository, private val coupons: CouponRepository,
    private val pools: PoolRepository, private val tx: PersistenceTransactions,
    private val locks: PersistenceLocks, private val commands: AdministrationCommands,
    private val printing: PrintOutbox, private val jdbc: JdbcTemplate
) {
    private fun quoteLocked(r: QuoteCouponRequest): Pair<QuoteCouponResponse, CouponQuote> {
        val dayId = RaceDayId(uuid(r.raceDayId));
        val raceId = RaceId(uuid(r.raceId))
        val ds = days.locked(dayId);
        val day = days.definition(dayId)
        val race = day.races.singleOrNull { it.id == raceId } ?: throw MissingRecord()
        locks.race(dayId, raceId)
        val state = persisted { races.state(dayId, raceId) } ?: throw MissingRecord()
        if (ds.phase != RaceDayPhase.RUNNING || state.phase != RacePhase.BETTING_OPEN) throw CommandPrecondition("Betting is not open")
        val type = bettingType(r.betType)
        val q = CouponPricingCalculator.quote(
            raceId, type, r.selection.domain(), race.roster, state.eligible(race),
            r.unitsPerCombination, day.pools.getValue(PoolKey(raceId, type)).unitStake, day.rules.maximumCouponLegs
        ).getOrThrow()
        val p = pricing(dayId, q, day.currency)
        return QuoteCouponResponse.newBuilder().setPricing(p).setRaceDayVersion(ds.version)
            .setRaceVersion(state.version)
            .setConfirmation(confirmation(q, p, ds.version, state.version)).build() to q
    }

    fun quote(r: QuoteCouponRequest): QuoteCouponResponse = tx.required { quoteLocked(r).first }
    fun purchase(r: PurchaseCouponRequest): PurchaseCouponResponse {
        val dayId = RaceDayId(uuid(r.raceDayId));
        val raceId = RaceId(uuid(r.raceId))
        val player = PlayerId(uuid(r.playerId));
        val couponId = CouponId(uuid(r.couponId))
        bettingType(r.betType); require(r.unitsPerCombination > 0 && r.quoteConfirmation.matches(Regex("[0-9a-f]{64}")))
        require(r.expectedDayVersion >= 0 && r.expectedRaceVersion >= 0 && (!r.hasExpectedAccountVersion() || r.expectedAccountVersion >= 0))
        require(r.pointOfSaleNumber > 0 && r.cashierIdentifier.length in 1..128 && r.cashierIdentifier.none { it.isISOControl() } && r.cashierIdentifier.isNotBlank())
        require(r.idempotencyKey.length in 1..256)
        val selection = r.selection.domain()
        // Canonicalize without expanding before reservation; Quinella positions are unordered.
        val canonical = if (r.betType == PoolType.QUINELLA && selection is CouponSelection.Positional)
            CouponSelection.Positional(selection.positions.sortedBy { if (it is PositionChoice.Horse) it.id.value.toString() else "~" }) else selection
        val normalized = r.toBuilder().setSelection(canonical.proto()).build()
        return commands.execute(
            "PurchaseCoupon", "${dayId.value}/${player.value}", r.idempotencyKey,
            normalized, PurchaseCouponResponse::parseFrom
        ) {
            val ds = days.locked(dayId)
            version(r.expectedDayVersion, ds.version)
            val day = days.definition(dayId)
            val race = day.races.singleOrNull { it.id == raceId } ?: throw MissingRecord()
            locks.race(dayId, raceId)
            val state = persisted { races.state(dayId, raceId) } ?: throw MissingRecord()
            version(r.expectedRaceVersion, state.version)
            if (accounts.projection(dayId, player) == null) throw MissingRecord()
            locks.accounts(dayId, listOf(player))
            val account = persisted { accounts.load(dayId, player) } ?: throw MissingRecord()
            if (r.hasExpectedAccountVersion()) version(r.expectedAccountVersion, account.version)
            val (quoted, q) = quoteLocked(
                QuoteCouponRequest.newBuilder().setRaceDayId(r.raceDayId).setRaceId(r.raceId)
                    .setBetType(r.betType).setSelection(canonical.proto()).setUnitsPerCombination(r.unitsPerCombination)
                    .build()
            )
            if (quoted.confirmation != r.quoteConfirmation) throw CommandPrecondition("Quote changed")
            val at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            val plan = BetPlacementPolicy.purchase(
                day, ds, race, state, account.entry, account, bettingType(r.betType),
                canonical, r.unitsPerCombination, couponId, at, q.terms
            ).getOrThrow()
            // Target pool lock precedes all child inserts.
            jdbc.queryForList(
                "SELECT race_id FROM pool_state WHERE race_id=? AND bet_type=? FOR UPDATE",
                raceId.value,
                r.betType.name
            )
            coupons.insert(
                plan.coupon,
                PurchaseMetadata(r.pointOfSaleNumber, r.cashierIdentifier),
                state.eligible(race)
            )
            val next = AccountProjector.apply(account, plan.debit).getOrThrow()
            accounts.append(account.version, next, at)
            pools.acceptPurchase(dayId, plan)
            val job = printing.enqueue(couponId.value, false)
            PurchaseCouponResponse.newBuilder().setCouponId(r.couponId).setPlayerId(r.playerId)
                .setPricing(quoted.pricing)
                .setPurchasedAt(at.toString()).setAccountVersion(next.version).setBalance(next.balance.amount)
                .setPrintJobId(job.toString()).setPrintingStatus(DispatchStatus.PENDING).build()
        }
    }

    private fun information(s: StoredCoupon): CouponInformation {
        val c = s.coupon
        val currency = days.definition(c.raceDayId).currency
        val p =
            CouponPricing.newBuilder().setRaceDayId(c.raceDayId.value.toString()).setRaceId(c.raceId.value.toString())
                .setBetType(PoolType.valueOf(c.betType.name)).setSelection(c.selection.proto())
                .setCombinationCount(c.legs.size)
                .setUnitsPerCombination(c.unitsPerCombination.value)
                .setTotalUnits(c.legs.fold(0L) { n, l -> Math.addExact(n, l.units.value) })
                .setUnitStake(c.unitStake.amount).setTotalStake(c.totalStake.amount).setCurrency(currency)
        return CouponInformation.newBuilder().setCouponId(c.id.value.toString())
            .setPlayerId(c.playerId.value.toString())
            .setPricing(p).setPurchasedAt(c.purchasedAt.toString()).setPointOfSaleNumber(s.metadata.posNumber)
            .setCashierIdentifier(s.metadata.cashierId).addAllLegs(c.legs.map { l ->
                PurchasedLeg.newBuilder().setCouponId(c.id.value.toString()).setOrdinal(l.id.ordinal)
                    .addAllHorseIds(l.bet.horses.map { it.value.toString() }).setUnits(l.units.value)
                    .setStake(l.stake.amount).build()
            }).build()
    }

    fun get(r: CouponQuery): CouponInformation = tx.required {
        information(persisted { coupons.load(RaceDayId(uuid(r.raceDayId)), CouponId(uuid(r.couponId))) }
            ?: throw MissingRecord())
    }

    fun list(r: ListPlayerCouponsRequest): CouponList = tx.required {
        val day = RaceDayId(uuid(r.raceDayId));
        val player = PlayerId(uuid(r.playerId))
        require(r.pageSize in 1..100 && r.offset >= 0)
        if (accounts.projection(day, player) == null) throw MissingRecord()
        val ids =
            jdbc.query(
                "SELECT id FROM coupon WHERE race_day_id=? AND player_id=? ORDER BY purchased_at,id LIMIT ? OFFSET ?",
                { rs, _ -> CouponId(rs.uuid("id")) }, day.value, player.value, r.pageSize + 1, r.offset
            )
        CouponList.newBuilder()
            .addAllCoupons(ids.take(r.pageSize).map { information(persisted { coupons.load(day, it) }!!) })
            .apply { if (ids.size > r.pageSize) nextOffset = Math.addExact(r.offset, r.pageSize) }.build()
    }
}

