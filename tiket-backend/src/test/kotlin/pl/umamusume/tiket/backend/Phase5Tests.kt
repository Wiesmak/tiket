package pl.umamusume.tiket.backend

import io.grpc.*
import pl.umamusume.tiket.core.validation.getOrThrow
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.grpc.client.ImportGrpcClients
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.umamusume.tiket.backend.application.*
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.printing.*
import java.io.*
import java.time.*
import java.util.*
import java.util.concurrent.*

@Testcontainers
@SpringBootTest(properties = ["spring.grpc.server.port=0", "tiket.printing.enabled=false"])
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(
    types = [RaceDayServiceGrpc.RaceDayServiceBlockingStub::class, RaceServiceGrpc.RaceServiceBlockingStub::class,
        EnrollmentServiceGrpc.EnrollmentServiceBlockingStub::class, BettingServiceGrpc.BettingServiceBlockingStub::class,
        PrintingServiceGrpc.PrintingServiceBlockingStub::class, ResultServiceGrpc.ResultServiceBlockingStub::class]
)
class Phase5Tests {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic
        @DynamicPropertySource
        fun database(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl); r.add(
                "spring.datasource.username",
                postgres::getUsername
            )
            r.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var days: RaceDayServiceGrpc.RaceDayServiceBlockingStub
    @Autowired
    lateinit var races: RaceServiceGrpc.RaceServiceBlockingStub
    @Autowired
    lateinit var players: EnrollmentServiceGrpc.EnrollmentServiceBlockingStub
    @Autowired
    lateinit var bets: BettingServiceGrpc.BettingServiceBlockingStub
    @Autowired
    lateinit var prints: PrintingServiceGrpc.PrintingServiceBlockingStub
    @Autowired
    lateinit var app: BettingApplicationService
    @Autowired
    lateinit var raceApp: RaceApplicationService
    @Autowired
    lateinit var outbox: PrintOutbox
    @Autowired
    lateinit var tx: PersistenceTransactions
    @Autowired
    lateinit var jdbc: JdbcTemplate
    @Autowired
    lateinit var accounts: AccountRepository
    @BeforeEach
    fun clearPrintQueue() {
        jdbc.update("DELETE FROM print_attempt")
        jdbc.update("DELETE FROM print_job")
    }

    fun id() = UUID.randomUUID().toString()
    data class Fixture(val day: String, val race: String, val player: String, val horses: List<String>)

    fun fixture(balance: Long = 10000, limit: Int = 10000, running: Boolean = true, open: Boolean = true, horseCount: Int = 5, take: Int = 1500, stake: Long = 10, promotional: Long = 0): Fixture {
        val f = Fixture(id(), id(), id(), (1..horseCount).map { id() })
        val race =
            RaceConfiguration.newBuilder().setRaceId(f.race).setNumber(1).setTitle("Race").setRacecourseName("Warsaw")
                .addAllRunners(f.horses.mapIndexed { n, h ->
                    Runner.newBuilder().setHorseId(h).setProgramNumber((n + 1).toString()).setDisplayName("Horse")
                        .build()
                })
                .addAllPools(listOf(PoolType.WIN, PoolType.QUINELLA, PoolType.EXACTA, PoolType.TRIFECTA).map {
                    PoolConfiguration.newBuilder().setType(it).setUnitStake(stake).setTakeBasisPoints(take).build()
                }).build()
        val config =
            RaceDayConfiguration.newBuilder().setRaceDayId(f.day).setEventDate("2026-11-10").setDisplayNumber(7)
                .setCurrency("PLN").setTimeZone("Europe/Warsaw").setRulesVersion("v1").setMaximumCouponLegs(limit)
                .setMaximumOpenRaces(1).setNoWinnerPolicy(NoWinnerPolicy.HOUSE_RETAINS_POOL).addRaces(race).build()
        days.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(config).setIdempotencyKey(id()).build())
        players.enrollPlayer(
            EnrollPlayerRequest.newBuilder().setRaceDayId(f.day).setPlayerId(f.player).setOpeningPaid(balance).setOpeningPromotional(promotional)
                .setIdempotencyKey(id()).build()
        )
        if (running) {
            days.publishRaceDay(DayCommand.newBuilder().setRaceDayId(f.day).setIdempotencyKey(id()).build())
            days.startRaceDay(
                DayCommand.newBuilder().setRaceDayId(f.day).setExpectedVersion(1).setIdempotencyKey(id()).build()
            )
            if (open) races.openBetting(raceCommand(f, 0))
        }
        return f
    }

    fun raceCommand(f: Fixture, v: Long) = RaceCommand.newBuilder().setRaceDayId(f.day).setRaceId(f.race)
        .setExpectedDayVersion(2).setExpectedRaceVersion(v).setIdempotencyKey(id()).build()

    fun positional(f: Fixture, vararg positions: Int) =
        BettingSelection.newBuilder().setPositional(
            PositionalSelection.newBuilder()
                .addAllPositions(positions.map {
                    Position.newBuilder().apply { if (it == 0) wall = true else horseId = f.horses[it - 1] }.build()
                })
        ).build()

    fun box(f: Fixture, vararg horses: Int) = BettingSelection.newBuilder().setBox(
        BoxSelection.newBuilder()
        .addAllHorseIds(horses.map { f.horses[it - 1] })
    ).build()

    fun quote(f: Fixture, type: PoolType, s: BettingSelection, q: Long = 2) = QuoteCouponRequest.newBuilder()
        .setRaceDayId(f.day).setRaceId(f.race).setBetType(type).setSelection(s).setUnitsPerCombination(q).build()

    fun purchase(
        f: Fixture,
        type: PoolType = PoolType.EXACTA,
        s: BettingSelection = box(f, 2, 3, 4),
        q: Long = 2
    ): PurchaseCouponRequest {
        val quote = bets.quoteCoupon(quote(f, type, s, q))
        return PurchaseCouponRequest.newBuilder().setRaceDayId(f.day).setRaceId(f.race).setPlayerId(f.player)
            .setCouponId(id())
            .setBetType(type).setSelection(s).setUnitsPerCombination(q).setQuoteConfirmation(quote.confirmation)
            .setExpectedDayVersion(quote.raceDayVersion).setExpectedRaceVersion(quote.raceVersion)
            .setPointOfSaleNumber(12).setCashierIdentifier("cashier").setIdempotencyKey(id()).build()
    }

    fun status(code: Status.Code, work: () -> Unit) {
        assertThatThrownBy(work).isInstanceOfSatisfying(StatusRuntimeException::class.java) {
            assertThat(it.status.code).isEqualTo(
                code
            )
        }
    }

    fun count(table: String, f: Fixture) = jdbc.queryForObject(
        "SELECT count(*) FROM $table WHERE coupon_id IN (SELECT id FROM coupon WHERE race_day_id=?)",
        Long::class.java,
        UUID.fromString(f.day)
    )

    fun account(f: Fixture) = accounts.load(RaceDayId(UUID.fromString(f.day)), PlayerId(UUID.fromString(f.player)))!!


    @Autowired lateinit var financial: RaceFinancialApplicationService
    @Autowired lateinit var resultRpc: ResultServiceGrpc.ResultServiceBlockingStub
    @Autowired lateinit var poolRepo: PoolRepository
    @Autowired lateinit var resolutionRepo: LegResolutionRepository
    @Autowired lateinit var couponRepo: CouponRepository

    fun query(f: Fixture) = RaceQuery.newBuilder().setRaceDayId(f.day).setRaceId(f.race).build()
    fun scratch(f: Fixture, horse: Int, v: Long) = ScratchHorseRequest.newBuilder()
        .setCommand(raceCommand(f, v)).setHorseId(f.horses[horse-1]).build()
    fun proposal(f: Fixture, v: Long, finishers: List<String> = f.horses.take(3)) = SubmitResultRequest.newBuilder()
        .setCommand(raceCommand(f, v)).setRevision(id()).setKind(OfficialResultKind.NORMAL)
        .addAllFinisherIds(finishers).build()
    fun verify(f: Fixture, revision: String, v: Long) = VerifyResultRequest.newBuilder()
        .setCommand(raceCommand(f, v)).setRevision(revision).build()
    fun settlement(f: Fixture, v: Long) = SettleRaceRequest.newBuilder().setCommand(raceCommand(f,v)).setSettlementId(id()).build()
    fun official(f: Fixture, v: Long = 1): Long {
        races.closeBetting(raceCommand(f,v))
        val p = proposal(f,v+1); resultRpc.submitResult(p)
        resultRpc.verifyResult(verify(f,p.revision,v+2))
        return v+3
    }
    fun reconcile(f: Fixture) {
        val day = RaceDayId(UUID.fromString(f.day)); val race = RaceId(UUID.fromString(f.race))
        val history = couponRepo.list(day,race).map { it.coupon }
        val resolutions = resolutionRepo.list(day,race)
        poolRepo.list(day,race).forEach { pool ->
            val actual = pl.umamusume.tiket.core.pool.PoolSnapshot.aggregate(pool.key,history,resolutions)
                .getOrThrow()
            assertThat(pool.units).isEqualTo(actual.units)
            assertThat(pool.validStake).isEqualTo(actual.validStake)
        }
    }

    @Test fun scratchPartialWallThenExplicitHorseAndImmutablePrintHistory() {
        val f = fixture()
        val bought = bets.purchaseCoupon(purchase(f,PoolType.EXACTA,positional(f,1,0)))
        val q = CouponQuery.newBuilder().setRaceDayId(f.day).setCouponId(bought.couponId).build()
        val original = bets.getCoupon(q)
        val oldQuote = purchase(f,PoolType.EXACTA,positional(f,1,0))
        val request = scratch(f,5,1)
        val ack = races.scratchHorse(request)
        assertThat(ack.refundedLegs).isEqualTo(1); assertThat(ack.refundedStake).isEqualTo(20)
        assertThat(account(f).balance.amount).isEqualTo(9940)
        assertThat(account(f).acceptedStakes.amount).isEqualTo(80)
        assertThat(account(f).qualifyingTurnover.amount).isEqualTo(60)
        assertThat(races.scratchHorse(request)).isEqualTo(ack)
        status(Status.Code.FAILED_PRECONDITION) { races.scratchHorse(scratch(f,5,2)) }
        status(Status.Code.ABORTED) { bets.purchaseCoupon(oldQuote) }
        status(Status.Code.ALREADY_EXISTS) { races.scratchHorse(request.toBuilder().setHorseId(f.horses[3]).build()) }
        status(Status.Code.NOT_FOUND) { races.scratchHorse(scratch(f,4,2).toBuilder().setHorseId(id()).build()) }
        reconcile(f)
        assertThat(races.scratchHorse(scratch(f,1,2)).refundedLegs).isEqualTo(3)
        assertThat(account(f).balance.amount).isEqualTo(10000)
        assertThat(bets.getCoupon(q)).isEqualTo(original)
        assertThat(count("print_job",f)).isEqualTo(1)
        reconcile(f)
    }

    @Test fun allPoolBoxesOverlappingScratchesThenVoid() {
        val f = fixture()
        listOf(PoolType.WIN,PoolType.QUINELLA,PoolType.EXACTA,PoolType.TRIFECTA).forEach {
            bets.purchaseCoupon(purchase(f,it,box(f,1,2,3,4,5)))
        }
        val a = races.scratchHorse(scratch(f,5,1))
        assertThat(a.refundedLegs).isEqualTo(49)
        reconcile(f)
        val b = races.scratchHorse(scratch(f,4,2))
        assertThat(b.refundedLegs).isEqualTo(28)
        reconcile(f)
        val request = raceCommand(f,3)
        val void = races.voidRace(request)
        assertThat(void.status.phase).isEqualTo(pl.umamusume.tiket.contracts.v1.RacePhase.VOIDED)
        assertThat(account(f).qualifyingTurnover.amount).isZero()
        assertThat(account(f).balance.amount).isEqualTo(10000)
        assertThat(races.voidRace(request)).isEqualTo(void)
        status(Status.Code.FAILED_PRECONDITION) { races.voidRace(raceCommand(f,4)) }
        status(Status.Code.FAILED_PRECONDITION) { races.scratchHorse(scratch(f,1,4)) }
        reconcile(f)
        assertThat(poolRepo.list(RaceDayId(UUID.fromString(f.day)),RaceId(UUID.fromString(f.race))).map { it.validStake.amount }).containsOnly(0L)
    }

    @Test fun voidEverySupportedPhaseAndProposalHistory() {
        for (stage in 0..3) {
            val f = fixture(open = stage > 0)
            var v = if (stage > 0) 1L else 0L
            if (stage > 0) bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,1)))
            if (stage > 1) { races.closeBetting(raceCommand(f,v)); v++ }
            if (stage > 2) { resultRpc.submitResult(proposal(f,v)); v++ }
            val ack = races.voidRace(raceCommand(f,v))
            assertThat(ack.status.phase).isEqualTo(pl.umamusume.tiket.contracts.v1.RacePhase.VOIDED)
            assertThat(resultRpc.getResult(query(f)).hasResult()).isFalse()
            assertThat(resultRpc.listResultRevisions(ResultRevisionsRequest.newBuilder().setRace(query(f)).setPageSize(10).build()).resultsCount).isEqualTo(if(stage > 2) 1 else 0)
            assertThat(account(f).balance.amount).isEqualTo(10000)
        }
    }

    @Test fun proposalsCorrectionInvalidationAndVerificationRules() {
        val f = fixture()
        races.closeBetting(raceCommand(f,1))
        val short = proposal(f,2,f.horses.take(1))
        resultRpc.submitResult(short)
        status(Status.Code.INVALID_ARGUMENT) { resultRpc.verifyResult(verify(f,short.revision,3)) }
        val corrected = proposal(f,3)
        resultRpc.submitResult(corrected)
        status(Status.Code.FAILED_PRECONDITION) { resultRpc.verifyResult(verify(f,short.revision,4)) }
        races.scratchHorse(scratch(f,5,4))
        assertThat(resultRpc.getResult(query(f)).hasResult()).isFalse()
        val selected = proposal(f,5)
        resultRpc.submitResult(selected)
        val verified = resultRpc.verifyResult(verify(f,selected.revision,6))
        assertThat(verified.result.status).isEqualTo(OfficialResultStatus.VERIFIED)
        assertThat(resultRpc.listResultRevisions(ResultRevisionsRequest.newBuilder().setRace(query(f)).setPageSize(2).build()).hasNextOffset()).isTrue()
        status(Status.Code.FAILED_PRECONDITION) { races.scratchHorse(scratch(f,4,7)) }
        status(Status.Code.FAILED_PRECONDITION) { races.voidRace(raceCommand(f,7)) }
        status(Status.Code.FAILED_PRECONDITION) { resultRpc.submitResult(proposal(f,7)) }
    }

    @Test fun invalidFinishersAndExceptionalKindAreRejected() {
        val f=fixture()
        races.scratchHorse(scratch(f,5,1)); races.closeBetting(raceCommand(f,2))
        listOf(emptyList(),listOf(f.horses[0],f.horses[0]),listOf(id()),listOf(f.horses[4])).forEach {
            status(Status.Code.INVALID_ARGUMENT) { resultRpc.submitResult(proposal(f,3,it)) }
        }
        status(Status.Code.FAILED_PRECONDITION) {
            resultRpc.submitResult(proposal(f,3).toBuilder().setKind(OfficialResultKind.EXCEPTIONAL).build())
        }
    }

    @Test fun fourPoolSettlementPartialWallWinnersLosersAndReplay() {
        val f=fixture()
        listOf(PoolType.WIN,PoolType.QUINELLA,PoolType.EXACTA,PoolType.TRIFECTA).forEach { type ->
            bets.purchaseCoupon(purchase(f,type,box(f,1,2,3,4,5)))
        }
        val wall = bets.purchaseCoupon(purchase(f,PoolType.EXACTA,positional(f,1,0)))
        races.scratchHorse(scratch(f,5,1))
        val v=official(f,2)
        val r=settlement(f,v)
        val ack=resultRpc.settleRace(r)
        assertThat(ack.poolsCount).isEqualTo(4)
        ack.poolsList.forEach {
            assertThat(it.validStake).isEqualTo(it.distributed+it.houseAllocation)
            assertThat(it.winningUnits).isEqualTo(if(it.type==PoolType.EXACTA) 4 else 2)
        }
        assertThat(resultRpc.settleRace(r)).isEqualTo(ack)
        status(Status.Code.FAILED_PRECONDITION) { resultRpc.settleRace(settlement(f,v+1)) }
        status(Status.Code.ALREADY_EXISTS) { resultRpc.settleRace(r.toBuilder().setSettlementId(id()).build()) }
        val stored=resultRpc.getSettlement(SettlementQuery.newBuilder().setRaceDayId(f.day).setRaceId(f.race).setSettlementId(r.settlementId).build())
        assertThat(stored).isEqualTo(ack)
        val terminal=resolutionRepo.list(RaceDayId(UUID.fromString(f.day)),RaceId(UUID.fromString(f.race)))
        assertThat(terminal.values.map { it.outcome }).contains(
            pl.umamusume.tiket.core.coupon.LegOutcome.WON,pl.umamusume.tiket.core.coupon.LegOutcome.LOST,pl.umamusume.tiket.core.coupon.LegOutcome.REFUNDED)
        assertThat(terminal.filterKeys { it.couponId.value.toString()==wall.couponId }.values.map { it.outcome }).contains(pl.umamusume.tiket.core.coupon.LegOutcome.WON)
        assertThat(count("print_job",f)).isEqualTo(5)
        reconcile(f)
        val page=resultRpc.listRaceLegResolutions(RaceLegResolutionsRequest.newBuilder().setRace(query(f)).setPageSize(3).build())
        assertThat(page.resolutionsCount).isEqualTo(3); assertThat(page.hasNextOffset()).isTrue()
        assertThat(account(f).winnings.amount).isEqualTo(ack.poolsList.sumOf { it.distributed })
    }

    @Test fun emptyAndNoWinnerPoolsSettle() {
        for(bet in listOf(false,true)) {
            val f=fixture()
            if(bet) bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,4)))
            val ack=resultRpc.settleRace(settlement(f,official(f)))
            assertThat(ack.poolsList.map { it.distributed }).containsOnly(0L)
            assertThat(ack.poolsList.sumOf { it.houseAllocation }).isEqualTo(if(bet) 20 else 0)
        }
    }

    // Fail at real SQL persistence boundaries, after earlier stages have executed in the same transaction.
    fun failWrites(table: String, work: () -> Unit) {
        jdbc.execute("""CREATE FUNCTION phase5_fail() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'injected phase5 failure'; END $$""")
        jdbc.execute("CREATE TRIGGER phase5_fail BEFORE INSERT OR UPDATE ON $table FOR EACH ROW EXECUTE FUNCTION phase5_fail()")
        try { work() } finally {
            jdbc.execute("DROP TRIGGER phase5_fail ON $table")
            jdbc.execute("DROP FUNCTION phase5_fail()")
        }
    }
    @Test fun refundFailureAfterCreditRollsBackAndRetryWorks() {
        val f=fixture(); bets.purchaseCoupon(purchase(f,PoolType.EXACTA,positional(f,1,0)))
        val r=scratch(f,5,1)
        failWrites("pool_state") { status(Status.Code.INTERNAL) { races.scratchHorse(r) } }
        assertThat(account(f).refundedStakes.amount).isZero()
        assertThat(resolutionRepo.list(RaceDayId(UUID.fromString(f.day)),RaceId(UUID.fromString(f.race)))).isEmpty()
        assertThat(races.getRaceStatus(query(f)).version).isEqualTo(1)
        assertThat(races.scratchHorse(r).refundedStake).isEqualTo(20)
        reconcile(f)
    }
    @Test fun verificationFailureBetweenStateAndResultRollsBack() {
        val f=fixture(); races.closeBetting(raceCommand(f,1))
        val p=proposal(f,2); resultRpc.submitResult(p)
        val r=verify(f,p.revision,3)
        failWrites("race_result") { status(Status.Code.INTERNAL) { resultRpc.verifyResult(r) } }
        assertThat(resultRpc.getResult(query(f)).result.status).isEqualTo(OfficialResultStatus.PROPOSED)
        assertThat(races.getRaceStatus(query(f)).version).isEqualTo(3)
        resultRpc.verifyResult(r)
    }
    @Test fun settlementPayoutFailureRollsBackFourPoolsCreditsAndResolutions() {
        val f=fixture(); bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,1)))
        val r=settlement(f,official(f))
        failWrites("payout") { status(Status.Code.INTERNAL) { resultRpc.settleRace(r) } }
        assertThat(account(f).winnings.amount).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement WHERE race_id=?",Long::class.java,UUID.fromString(f.race))).isZero()
        assertThat(races.getRaceStatus(query(f)).phase).isEqualTo(pl.umamusume.tiket.contracts.v1.RacePhase.RESULT_VERIFIED)
        assertThat(resultRpc.settleRace(r).poolsCount).isEqualTo(4)
    }

    fun compete(work: (Int) -> Unit): List<Throwable?> {
        val barrier=CyclicBarrier(2); val executor=Executors.newFixedThreadPool(2)
        try {
            return (0..1).map { i -> executor.submit<Throwable?> {
                try { tx.required { jdbc.queryForObject("SELECT 1",Int::class.java); barrier.await(15,TimeUnit.SECONDS); work(i); Unit }; null }
                catch(t: Throwable) { t }
            } }.map { it.get(30,TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }
    @Test fun concurrentSameKeyRefundAndSettlementExecuteOnce() {
        val f=fixture(); bets.purchaseCoupon(purchase(f,PoolType.EXACTA,positional(f,1,0)))
        val scratch=scratch(f,5,1)
        assertThat(compete { financial.scratch(scratch) }).containsOnlyNulls()
        assertThat(account(f).refundedStakes.amount).isEqualTo(20)
        val r=settlement(f,official(f,2))
        assertThat(compete { financial.settle(r) }).containsOnlyNulls()
        assertThat(account(f).winnings.amount).isEqualTo(50)
        val g=fixture(); val v=official(g)
        val commands=(0..1).map { settlement(g,v) }
        assertThat(compete { financial.settle(commands[it]) }.count { it==null }).isEqualTo(1)
    }
    @Test fun concurrentOverlappingScratchesAndVoidNeverDoubleRefund() {
        val f=fixture(); bets.purchaseCoupon(purchase(f,PoolType.TRIFECTA,box(f,1,2,3,4,5)))
        val a=scratch(f,4,1); val b=scratch(f,5,1)
        assertThat(compete { financial.scratch(if(it==0) a else b) }.count { it==null }).isEqualTo(1)
        reconcile(f)
        races.voidRace(raceCommand(f,2))
        assertThat(account(f).balance.amount).isEqualTo(10000)
        reconcile(f)
        val g=fixture(); bets.purchaseCoupon(purchase(g))
        assertThat(compete { if(it==0) financial.scratch(scratch(g,4,1)) else financial.voidRace(raceCommand(g,1)) }
            .count { it==null }).isEqualTo(1)
        val state=races.getRaceStatus(query(g))
        if(state.phase != pl.umamusume.tiket.contracts.v1.RacePhase.VOIDED) races.voidRace(raceCommand(g,state.version))
        assertThat(account(g).balance.amount).isEqualTo(10000)
    }

    @Test fun roundingReferenceAndTakeBoundariesThroughGrpc() {
        for(take in listOf(0,1500,10000)) {
            val f=fixture(take=take,stake=1)
            bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,1),3))
            bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,4),998))
            val ack=resultRpc.settleRace(settlement(f,official(f)))
            val win=ack.poolsList.single { it.type==PoolType.WIN }
            assertThat(win.validStake).isEqualTo(1001)
            if(take==1500) {
                assertThat(win.distributable).isEqualTo(850)
                assertThat(win.dividendPerUnit).isEqualTo(283)
                assertThat(win.distributed).isEqualTo(849)
                assertThat(win.houseAllocation).isEqualTo(152)
            }
            if(take==10000) {
                assertThat(win.distributed).isZero()
                val rs=resolutionRepo.list(RaceDayId(UUID.fromString(f.day)),RaceId(UUID.fromString(f.race)))
                assertThat(rs.values.filter { it.outcome==pl.umamusume.tiket.core.coupon.LegOutcome.WON }).hasSize(1)
            }
            assertThat(account(f).winnings.amount).isEqualTo(win.distributed)
        }
    }
    @Test fun sharedSelectionsMultiplePlayersAndPromotionalTurnover() {
        val f=fixture(running=false,promotional=500)
        val second=id()
        players.enrollPlayer(EnrollPlayerRequest.newBuilder().setRaceDayId(f.day).setPlayerId(second)
            .setOpeningPaid(1000).setIdempotencyKey(id()).build())
        days.publishRaceDay(DayCommand.newBuilder().setRaceDayId(f.day).setIdempotencyKey(id()).build())
        days.startRaceDay(DayCommand.newBuilder().setRaceDayId(f.day).setExpectedVersion(1).setIdempotencyKey(id()).build())
        races.openBetting(raceCommand(f,0))
        for(player in listOf(f.player,second)) for(type in listOf(PoolType.WIN,PoolType.EXACTA)) {
            bets.purchaseCoupon(purchase(f,type,box(f,1,2,3,4,5)).toBuilder().setPlayerId(player).build())
        }
        val refund=races.scratchHorse(scratch(f,5,1))
        assertThat(refund.playersCount).isEqualTo(2)
        assertThat(account(f).acceptedStakes.amount).isEqualTo(500)
        assertThat(account(f).qualifyingTurnover.amount).isEqualTo(320)
        val ack=resultRpc.settleRace(settlement(f,official(f,2)))
        assertThat(ack.playersCount).isEqualTo(2)
        assertThat(ack.playersList.map { it.amount }.distinct()).hasSize(1)
        reconcile(f)
    }
    @Test fun corruptedProjectionAndMissingScratchRefundAbortSettlement() {
        for(missing in listOf(false,true)) {
            val f=fixture(); bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,4)))
            val v=official(f); val r=settlement(f,v)
            assertThatThrownBy { tx.required {
                if(missing) jdbc.update("INSERT INTO race_scratch VALUES (?,?,clock_timestamp())",UUID.fromString(f.race),UUID.fromString(f.horses[3]))
                else {
                    jdbc.update("UPDATE pool_state SET active_units=active_units+2,valid_stake=valid_stake+20 WHERE race_id=? AND bet_type='WIN'",UUID.fromString(f.race))
                    jdbc.update("UPDATE pool_selection_units SET units=units+2 WHERE race_id=? AND bet_type='WIN'",UUID.fromString(f.race))
                }
                assertThatThrownBy { financial.settle(r) }.isInstanceOf(InvalidPersistenceException::class.java)
                error("rollback fixture corruption")
            } }.isInstanceOf(IllegalStateException::class.java)
            assertThat(account(f).winnings.amount).isZero()
            resultRpc.settleRace(r)
        }
    }
    @Test fun restartReconstructsVerifiedResultSettlementAndOriginalAcknowledgement() {
        val f=fixture(); bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,1)))
        val v=official(f); val verified=resultRpc.getResult(query(f))
        val r=settlement(f,v)
        val originalAck=resultRpc.settleRace(r)
        org.springframework.boot.builder.SpringApplicationBuilder(TiketBackendApplication::class.java).run(
            "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}", "--spring.grpc.server.port=0", "--tiket.printing.enabled=false"
        ).use { restarted ->
            val service=restarted.getBean(RaceFinancialApplicationService::class.java)
            assertThat(service.getResult(query(f)).result).isEqualTo(verified.result)
            val ack=service.settle(r)
            assertThat(ack).isEqualTo(originalAck)
            assertThat(resultRpc.settleRace(r)).isEqualTo(ack)
            assertThat(service.getSettlement(SettlementQuery.newBuilder().setRaceDayId(f.day).setRaceId(f.race).setSettlementId(r.settlementId).build())).isEqualTo(ack)
        }
    }
    @Test fun purchaseVersusScratchAndScratchVersusVerifyRespectWinningLock() {
        for(scratchFirst in listOf(false,true)) {
            val f=fixture(); val buy=purchase(f,PoolType.EXACTA,positional(f,1,0))
            val executor=Executors.newSingleThreadExecutor(); val started=CountDownLatch(1)
            try {
                lateinit var future: Future<Throwable?>
                tx.required {
                    jdbc.queryForList("SELECT * FROM race_day_state WHERE race_day_id=? FOR UPDATE",UUID.fromString(f.day))
                    future=executor.submit<Throwable?> {
                        started.countDown()
                        try { if(scratchFirst) app.purchase(buy) else financial.scratch(scratch(f,5,1)); null } catch(t: Throwable) { t }
                    }
                    assertThat(started.await(5,TimeUnit.SECONDS)).isTrue()
                    if(scratchFirst) financial.scratch(scratch(f,5,1)) else app.purchase(buy)
                    Unit
                }
                val outcome=future.get(20,TimeUnit.SECONDS)
                if(scratchFirst) assertThat(outcome).isInstanceOf(StaleVersionException::class.java) else assertThat(outcome).isNull()
                assertThat(account(f).refundedStakes.amount).isEqualTo(if(scratchFirst) 0 else 20)
                reconcile(f)
            } finally { executor.shutdownNow() }
        }
        val f=fixture(); races.closeBetting(raceCommand(f,1))
        val p=proposal(f,2); resultRpc.submitResult(p)
        val outcomes=compete { if(it==0) financial.scratch(scratch(f,5,3)) else financial.verify(verify(f,p.revision,3)) }
        assertThat(outcomes.count { it==null }).isEqualTo(1)
        val final=races.getRaceStatus(query(f))
        assertThat(final.phase).isIn(pl.umamusume.tiket.contracts.v1.RacePhase.BETTING_CLOSED,pl.umamusume.tiket.contracts.v1.RacePhase.RESULT_VERIFIED)
    }

    @Test fun largePurchasedCouponRefundAndSettlementUseFrozenConcreteLegs() {
        val f=fixture(balance=100000,horseCount=101,limit=9900)
        val ack=bets.purchaseCoupon(purchase(f,PoolType.EXACTA,box(f,*(1..100).toList().toIntArray()),1))
        val started=System.nanoTime()
        val refund=races.scratchHorse(scratch(f,100,1))
        assertThat(refund.refundedLegs).isEqualTo(198)
        reconcile(f)
        val settled=resultRpc.settleRace(settlement(f,official(f,2)))
        assertThat(settled.poolsList.single { it.type==PoolType.EXACTA }.validStake).isEqualTo(97020)
        assertThat(count("coupon_leg",f)).isEqualTo(9900)
        assertThat(account(f).balance.amount).isEqualTo(85447)
        println("Phase5 9900-leg scratch+settlement elapsedMs="+(System.nanoTime()-started)/1000000)
        assertThat(count("print_job",f)).isEqualTo(1)
    }
    @Test fun winningsBalanceOverflowRollsBackSettlement() {
        val f=fixture(balance=Long.MAX_VALUE,running=false,take=0)
        val second=id()
        players.enrollPlayer(EnrollPlayerRequest.newBuilder().setRaceDayId(f.day).setPlayerId(second)
            .setOpeningPaid(1000).setIdempotencyKey(id()).build())
        days.publishRaceDay(DayCommand.newBuilder().setRaceDayId(f.day).setIdempotencyKey(id()).build())
        days.startRaceDay(DayCommand.newBuilder().setRaceDayId(f.day).setExpectedVersion(1).setIdempotencyKey(id()).build())
        races.openBetting(raceCommand(f,0))
        bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,1),1))
        bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,4),1).toBuilder().setPlayerId(second).build())
        val r=settlement(f,official(f))
        status(Status.Code.INVALID_ARGUMENT) { resultRpc.settleRace(r) }
        assertThat(account(f).winnings.amount).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement WHERE race_id=?",Long::class.java,UUID.fromString(f.race))).isZero()
    }

    @Test fun scheduledScratchAndFinalizingFinancialCommands() {
        val f=fixture(open=false)
        val scheduled=races.scratchHorse(scratch(f,5,0))
        assertThat(scheduled.refundedLegs).isZero()
        assertThat(scheduled.status.scratchedHorseIdsList).containsExactly(f.horses[4])
        races.openBetting(raceCommand(f,1))
        bets.purchaseCoupon(purchase(f,PoolType.EXACTA,positional(f,1,0)))
        races.closeBetting(raceCommand(f,2))
        days.finalizeRaceDay(DayCommand.newBuilder().setRaceDayId(f.day).setExpectedVersion(2).setIdempotencyKey(id()).build())
        val r=scratch(f,4,3).toBuilder().setCommand(raceCommand(f,3).toBuilder().setExpectedDayVersion(3)).build()
        races.scratchHorse(r)
        val p=proposal(f,4).toBuilder().setCommand(raceCommand(f,4).toBuilder().setExpectedDayVersion(3)).build()
        resultRpc.submitResult(p)
        resultRpc.verifyResult(verify(f,p.revision,5).toBuilder().setCommand(raceCommand(f,5).toBuilder().setExpectedDayVersion(3)).build())
        val settled=resultRpc.settleRace(settlement(f,6).toBuilder().setCommand(raceCommand(f,6).toBuilder().setExpectedDayVersion(3)).build())
        assertThat(settled.status.phase).isEqualTo(pl.umamusume.tiket.contracts.v1.RacePhase.SETTLED)
        val other=fixture(open=false)
        days.finalizeRaceDay(DayCommand.newBuilder().setRaceDayId(other.day).setExpectedVersion(2).setIdempotencyKey(id()).build())
        races.voidRace(raceCommand(other,0).toBuilder().setExpectedDayVersion(3).build())
    }
}
