package pl.umamusume.tiket.backend.application

import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.accounting.AccountProjector
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.pool.PoolSnapshot
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.race.RacePhase as Phase
import pl.umamusume.tiket.core.raceday.RaceDayPhase
import pl.umamusume.tiket.core.settlement.*
import pl.umamusume.tiket.core.validation.getOrThrow

private fun moneySum(values: Iterable<Money>) = values.fold(Money.ZERO) { total, amount -> total + amount }

@Service
class RaceFinancialApplicationService(
    private val days: RaceDayApplicationService, private val races: RaceRepository,
    private val accounts: AccountRepository, private val coupons: CouponRepository,
    private val pools: PoolRepository, private val resolutions: LegResolutionRepository,
    private val results: ResultRepository, private val settlements: SettlementRepository,
    private val tx: PersistenceTransactions, private val locks: PersistenceLocks,
    private val commands: AdministrationCommands, private val jdbc: JdbcTemplate
) {
    private data class Context(val day: pl.umamusume.tiket.core.raceday.RaceDayDefinition,
                               val race: RaceDefinition, val state: RaceState)
    private fun locked(c: RaceCommand): Context {
        val dayId = RaceDayId(uuid(c.raceDayId))
        val raceId = RaceId(uuid(c.raceId))
        val ds = days.locked(dayId)
        version(c.expectedDayVersion, ds.version)
        val day = days.definition(dayId)
        val race = day.races.singleOrNull { it.id == raceId } ?: throw MissingRecord()
        locks.race(dayId, raceId)
        val state = persisted { races.state(dayId, raceId) } ?: throw MissingRecord()
        version(c.expectedRaceVersion, state.version)
        if (ds.phase !in setOf(RaceDayPhase.RUNNING, RaceDayPhase.FINALIZING))
            throw CommandPrecondition("Day must be running or finalizing")
        return Context(day, race, state)
    }
    private fun validate(c: RaceCommand) {
        uuid(c.raceDayId); uuid(c.raceId)
        require(c.expectedDayVersion >= 0 && c.expectedRaceVersion >= 0)
        require(c.idempotencyKey.isNotBlank() && c.idempotencyKey.length <= 256)
    }
    private fun scope(c: RaceCommand) = "${uuid(c.raceDayId)}/${uuid(c.raceId)}"
    private fun credits(day: RaceDayId, credits: List<PlayerCredit>) {
        credits.groupBy { it.playerId }.toSortedMap(compareBy { it.value.toString() }).forEach { (player, list) ->
            val before = persisted { accounts.load(day, player) } ?: throw InvalidPersistenceException("Missing player")
            val next = list.fold(before) { a, credit -> AccountProjector.apply(a, credit.movement).getOrThrow() }
            accounts.append(before.version, next)
            persisted { accounts.load(day, player) }
        }
    }
    private fun reconcile(day: RaceDayId, race: RaceId, history: List<pl.umamusume.tiket.core.coupon.Coupon>,
                          existing: Map<BetLegId, pl.umamusume.tiket.core.coupon.LegResolution>): Map<PoolKey, PoolSnapshot> {
        val snapshots = persisted { pools.list(day, race) }.associateBy { it.key }
        if (snapshots.size != 4) throw InvalidPersistenceException("Four pools required")
        snapshots.forEach { (key, s) ->
            val aggregate = persisted { PoolSnapshot.aggregate(key, history, existing).getOrThrow() }
            if (s.units != aggregate.units || s.validStake != aggregate.validStake)
                throw InvalidPersistenceException("Pool differs from active purchased legs")
        }
        return snapshots
    }
    fun scratch(r: ScratchHorseRequest): RefundAcknowledgement {
        require(r.hasCommand()); validate(r.command)
        val horse = HorseId(uuid(r.horseId))
        return commands.execute("ScratchHorse", scope(r.command), r.command.idempotencyKey, r,
            RefundAcknowledgement::parseFrom) { refund(r.command, RefundReason.Scratch(horse)) }
    }
    fun voidRace(c: RaceCommand): RefundAcknowledgement {
        validate(c)
        return commands.execute("VoidRace", scope(c), c.idempotencyKey, c,
            RefundAcknowledgement::parseFrom) { refund(c, RefundReason.VoidRace) }
    }
    private fun refund(c: RaceCommand, reason: RefundReason): RefundAcknowledgement {
        val (day, race, state) = locked(c)
        if (state.phase !in setOf(Phase.SCHEDULED, Phase.BETTING_OPEN, Phase.BETTING_CLOSED, Phase.RESULT_PROPOSED))
            throw CommandPrecondition("Race cannot be refunded")
        if (reason is RefundReason.Scratch) {
            if (reason.horseId !in race.roster) throw MissingRecord()
            if (reason.horseId in state.scratched) throw CommandPrecondition("Horse already scratched")
        }
        val history = persisted { coupons.list(day.id, race.id).map { it.coupon } }
        val affected = history.filter { coupon -> reason == RefundReason.VoidRace ||
            coupon.legs.any { (reason as RefundReason.Scratch).horseId in it.bet.horses } }.map { it.playerId }
        locks.accounts(day.id, affected)
        locks.pools(day.id, race.id)
        val existing = persisted { resolutions.list(day.id, race.id) }
        val snapshots = reconcile(day.id, race.id, history, existing)
        val plan = persisted { RefundPlanner.plan(race, state, reason, history, existing).getOrThrow() }
        resolutions.insertAll(day.id, history, plan.resolutions)
        credits(day.id, plan.credits)
        plan.poolReductions.keys.sortedBy { it.betType.name }.forEach { key ->
            pools.applyRefundDelta(day.id, snapshots.getValue(key).version, key, plan)
        }
        reconcile(day.id, race.id, history, existing + plan.resolutions.associateBy { it.legId })
        races.update(day.id, state.version, plan.resultingState)
        return RefundAcknowledgement.newBuilder().setStatus(raceAck(day.id, plan.resultingState))
            .setRefundedLegs(plan.resolutions.size.toLong()).setRefundedStake(moneySum(plan.playerTotals.values).amount)
            .addAllPlayers(plan.playerTotals.map { (p, m) -> playerSummary(p, m) })
            .addAllPools(plan.poolReductions.map { (key, d) -> PoolRefundSummary.newBuilder()
                .setType(PoolType.valueOf(key.betType.name)).setRefundedStake(d.stake.amount).setRefundedUnits(d.units).build() }).build()
    }
    private fun official(day: RaceDayId, r: RaceResult): OfficialResult = OfficialResult.newBuilder()
        .setRaceDayId(day.value.toString()).setRaceId(r.raceId.value.toString()).setRevision(r.revision)
        .addAllFinisherIds(r.finishers.map { it.value.toString() })
        .setKind(OfficialResultKind.valueOf(r.kind.name)).setStatus(OfficialResultStatus.valueOf(r.status.name)).build()
    private fun resultAck(day: RaceDayId, state: RaceState) = ResultAcknowledgement.newBuilder()
        .setStatus(raceAck(day, state)).apply { state.result?.let { setResult(official(day, it)) } }.build()
    fun submit(r: SubmitResultRequest): ResultAcknowledgement {
        require(r.hasCommand()); validate(r.command)
        require(r.revision.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
        require(r.kind in setOf(OfficialResultKind.NORMAL, OfficialResultKind.EXCEPTIONAL))
        val finishers = r.finisherIdsList.map { HorseId(uuid(it)) }
        return commands.execute("SubmitResult", scope(r.command), r.command.idempotencyKey, r, ResultAcknowledgement::parseFrom) {
            val (day, race, state) = locked(r.command)
            if (state.phase !in setOf(Phase.BETTING_CLOSED, Phase.RESULT_PROPOSED))
                throw CommandPrecondition("Betting must be closed")
            val result = RaceResult(race.id, r.revision, finishers, ResultStatus.PROPOSED, ResultKind.valueOf(r.kind.name))
            ResultValidator.validate(race, state, result).getOrThrow()
            val next = RaceLifecyclePolicy.transition(race, state, Phase.RESULT_PROPOSED, result).getOrThrow()
            results.insert(day.id, result)
            races.update(day.id, state.version, next)
            resultAck(day.id, next)
        }
    }
    fun verify(r: VerifyResultRequest): ResultAcknowledgement {
        require(r.hasCommand()); validate(r.command); require(r.revision.isNotBlank())
        return commands.execute("VerifyResult", scope(r.command), r.command.idempotencyKey, r, ResultAcknowledgement::parseFrom) {
            val (day, race, state) = locked(r.command)
            val proposed = state.result
            if (state.phase != Phase.RESULT_PROPOSED || proposed == null || proposed.revision != r.revision)
                throw CommandPrecondition("Verification must confirm selected proposal")
            val verified = RaceResult(race.id, proposed.revision, proposed.finishers, ResultStatus.VERIFIED, proposed.kind)
            val next = RaceLifecyclePolicy.transition(race, state, Phase.RESULT_VERIFIED, verified).getOrThrow()
            // update() rereads the old proposed state; do not verify its row first.
            races.update(day.id, state.version, next)
            results.verify(day.id, race.id, proposed.revision)
            val final = persisted { races.state(day.id, race.id) }!!
            if (final.phase != Phase.RESULT_VERIFIED || final.result?.status != ResultStatus.VERIFIED)
                throw InvalidPersistenceException("Incomplete verification")
            resultAck(day.id, final)
        }
    }
    fun settle(r: SettleRaceRequest): SettlementInformation {
        require(r.hasCommand()); validate(r.command)
        val id = SettlementId(uuid(r.settlementId))
        return commands.execute("SettleRace", scope(r.command), r.command.idempotencyKey, r, SettlementInformation::parseFrom) {
            val (day, race, state) = locked(r.command)
            if (state.phase != Phase.RESULT_VERIFIED) throw CommandPrecondition("Verified race required")
            if (settlements.forRace(day.id, race.id).isNotEmpty()) throw CommandPrecondition("Already settled")
            val history = persisted { coupons.list(day.id, race.id).map { it.coupon } }
            locks.accounts(day.id, history.map { it.playerId })
            history.map { it.playerId }.distinct().sortedBy { it.value.toString() }.forEach {
                persisted { accounts.load(day.id, it) } ?: throw InvalidPersistenceException("Missing account")
            }
            locks.pools(day.id, race.id)
            val existing = persisted { resolutions.list(day.id, race.id) }
            val snapshots = reconcile(day.id, race.id, history, existing)
            val plan = persisted { RaceSettlementPlanner.plan(id, race, state,
                BetType.entries.associateWith { day.pools.getValue(PoolKey(race.id, it)) },
                snapshots, history, existing).getOrThrow() }
            val stored = StoredSettlement(id, race.id, plan.resultRevision, plan.pools, plan.expectedPoolVersions)
            settlements.insert(day.id, stored)
            resolutions.insertAll(day.id, history, plan.resolutions)
            credits(day.id, plan.credits)
            plan.credits.forEach { credit ->
                val m = credit.movement
                settlements.insertPayout(day.id, StoredPayout(id, credit.playerId, m.legId!!, m.amount, m.key))
            }
            val actualResolutions = resolutions.list(day.id, race.id)
            if (actualResolutions != existing + plan.resolutions.associateBy { it.legId })
                throw InvalidPersistenceException("Incomplete terminal resolutions")
            val payouts = settlements.payouts(day.id, id)
            if (payouts.size != plan.credits.size || moneySum(payouts.map { it.amount }) != moneySum(plan.playerPayouts.values))
                throw InvalidPersistenceException("Payout reconciliation failed")
            plan.pools.forEach { (key, pool) ->
                val originals = history.filter { it.betType == key.betType }
                val refunds = originals.flatMap { it.legs }.filter { existing[it.id]?.outcome == pl.umamusume.tiket.core.coupon.LegOutcome.REFUNDED }
                FinancialReconciliation.validate(moneySum(originals.map { it.totalStake }),
                    moneySum(refunds.map { it.stake }), pool.distributed, pool.houseAllocation).getOrThrow()
            }
            val persistedSettlement = settlements.load(day.id, id)
            if (persistedSettlement?.pools != plan.pools || persistedSettlement.poolVersions != plan.expectedPoolVersions)
                throw InvalidPersistenceException("Stored settlement differs from plan")
            races.update(day.id, state.version, plan.resultingState)
            persisted { races.state(day.id, race.id) }
            settlementInfo(day.id, plan.resultingState, stored)
        }
    }
    private fun playerSummary(p: PlayerId, m: Money) = PlayerFinancialSummary.newBuilder().setPlayerId(p.value.toString()).setAmount(m.amount).build()
    private fun settlementInfo(day: RaceDayId, state: RaceState, s: StoredSettlement): SettlementInformation {
        val totals = settlements.payouts(day, s.id).groupBy { it.playerId }.mapValues { moneySum(it.value.map { p -> p.amount }) }
        return SettlementInformation.newBuilder().setSettlementId(s.id.value.toString()).setStatus(raceAck(day, state))
            .setResultRevision(s.resultRevision).addAllPools(s.pools.toSortedMap(compareBy { it.betType.ordinal }).map { (key, p) ->
                PoolSettlementSummary.newBuilder().setType(PoolType.valueOf(key.betType.name))
                    .setValidStake(p.validStake.amount).setWinningUnits(p.winningUnits).setDistributable(p.distributable.amount)
                    .setDividendPerUnit(p.dividendPerUnit.amount).setDistributed(p.distributed.amount)
                    .setHouseAllocation(p.houseAllocation.amount).setPoolVersion(s.poolVersions.getValue(key)).build()
            }).addAllPlayers(totals.toSortedMap(compareBy { it.value.toString() }).map { (p, m) -> playerSummary(p, m) }).build()
    }
    private fun readLocked(r: RaceQuery): Pair<RaceDayId, RaceState> {
        val day = RaceDayId(uuid(r.raceDayId)); val race = RaceId(uuid(r.raceId))
        days.locked(day)
        if (races.load(day, race) == null) throw MissingRecord()
        locks.race(day, race)
        return day to (persisted { races.state(day, race) } ?: throw MissingRecord())
    }
    fun getResult(r: RaceQuery): ResultAcknowledgement = tx.required {
        val (day, state) = readLocked(r)
        resultAck(day, state)
    }
    fun getSettlement(r: SettlementQuery): SettlementInformation = tx.required {
        val (day, state) = readLocked(RaceQuery.newBuilder().setRaceDayId(r.raceDayId).setRaceId(r.raceId).build())
        val stored = settlements.load(day, SettlementId(uuid(r.settlementId))) ?: throw MissingRecord()
        if (stored.raceId != state.raceId) throw MissingRecord()
        settlementInfo(day, state, stored)
    }
    fun revisions(r: ResultRevisionsRequest): ResultRevisions = tx.required {
        require(r.hasRace() && r.pageSize in 1..100 && r.offset >= 0)
        val (day, state) = readLocked(r.race)
        val ids = jdbc.query("SELECT revision FROM race_result WHERE race_id=? ORDER BY sequence LIMIT ? OFFSET ?",
            { rs, _ -> rs.getString(1) }, state.raceId.value, r.pageSize + 1, r.offset)
        ResultRevisions.newBuilder().addAllResults(ids.take(r.pageSize).map { official(day, results.load(day, state.raceId, it)!!) })
            .apply { if (ids.size > r.pageSize) nextOffset = Math.addExact(r.offset, r.pageSize) }.build()
    }
    fun legResolutions(r: RaceLegResolutionsRequest): RaceLegResolutions = tx.required {
        require(r.hasRace() && r.pageSize in 1..500 && r.offset >= 0)
        val (day, state) = readLocked(r.race)
        val rows = jdbc.query("""SELECT l.* FROM leg_resolution l JOIN coupon c ON c.id=l.coupon_id
            WHERE c.race_day_id=? AND c.race_id=? ORDER BY l.coupon_id,l.ordinal LIMIT ? OFFSET ?""",
            { rs, _ -> TerminalLegResolution.newBuilder().setCouponId(rs.getString("coupon_id"))
                .setOrdinal(rs.getInt("ordinal")).setOutcome(TerminalLegOutcome.valueOf(rs.getString("outcome")))
                .setAmount(rs.getLong("amount")).apply { rs.getString("settlement_id")?.let { settlementId = it } }.build()
            }, day.value, state.raceId.value, r.pageSize + 1, r.offset)
        RaceLegResolutions.newBuilder().addAllResolutions(rows.take(r.pageSize))
            .apply { if (rows.size > r.pageSize) nextOffset = Math.addExact(r.offset, r.pageSize) }.build()
    }
}
