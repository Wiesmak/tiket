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
        PrintingServiceGrpc.PrintingServiceBlockingStub::class, ResultServiceGrpc.ResultServiceBlockingStub::class,
        AccountServiceGrpc.AccountServiceBlockingStub::class]
)
class Phase6Tests {
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


    @Autowired lateinit var accountRpc: AccountServiceGrpc.AccountServiceBlockingStub
    @Autowired lateinit var accountApp: AccountApplicationService
    @Autowired lateinit var dayApp: RaceDayApplicationService
    fun aq(f: Fixture) = AccountQuery.newBuilder().setRaceDayId(f.day).setPlayerId(f.player).build()
    fun dc(f: Fixture, version: Long) = DayCommand.newBuilder().setRaceDayId(f.day).setExpectedVersion(version).setIdempotencyKey(id()).build()
    fun complete(f: Fixture): DayAcknowledgement {
        days.finalizeRaceDay(dc(f,2))
        return days.completeRaceDay(dc(f,3))
    }
    fun cash(f: Fixture): CashOutRequest {
        val q=accountRpc.getWithdrawalQuote(aq(f))
        return CashOutRequest.newBuilder().setRaceDayId(f.day).setPlayerId(f.player)
            .setExpectedDayVersion(q.raceDayVersion).setExpectedAccountVersion(q.account.accountVersion)
            .setIdempotencyKey(id()).build()
    }
    @Test fun noBetVoidCashoutReceiptAndDurableReplayAfterClosure() {
        val f=fixture(balance=100,promotional=100,open=false)
        status(Status.Code.FAILED_PRECONDITION) { accountRpc.getWithdrawalQuote(aq(f)) }
        status(Status.Code.FAILED_PRECONDITION) { accountRpc.getCashoutReceipt(aq(f)) }
        races.voidRace(raceCommand(f,0))
        days.finalizeRaceDay(dc(f,2))
        val completion=dc(f,3); val completed=days.completeRaceDay(completion)
        assertThat(completed.phase).isEqualTo(DayPhase.COMPLETED)
        status(Status.Code.FAILED_PRECONDITION) { days.closeRaceDay(dc(f,4)) }
        val q=accountRpc.getWithdrawalQuote(aq(f))
        assertThat(q.withdrawable).isEqualTo(100); assertThat(q.forfeitable).isEqualTo(100)
        val request=cash(f); val ack=accountRpc.cashOut(request)
        assertThat(ack.withdrawn).isEqualTo(100); assertThat(ack.promotionalForfeiture).isEqualTo(100)
        assertThat(ack.accountVersion).isEqualTo(1); assertThat(ack.balance).isZero()
        assertThat(accountRpc.getCashoutReceipt(aq(f))).isEqualTo(ack)
        assertThat(accountRpc.getAccount(aq(f)).cashedOut).isTrue()
        status(Status.Code.FAILED_PRECONDITION) { accountRpc.getWithdrawalQuote(aq(f)) }
        status(Status.Code.FAILED_PRECONDITION) { accountRpc.cashOut(request.toBuilder().setIdempotencyKey(id()).setExpectedAccountVersion(1).build()) }
        status(Status.Code.ALREADY_EXISTS) { accountRpc.cashOut(request.toBuilder().setExpectedAccountVersion(1).build()) }
        val close=dc(f,4); val closed=days.closeRaceDay(close)
        assertThat(closed.phase).isEqualTo(DayPhase.CLOSED)
        assertThat(days.closeRaceDay(close)).isEqualTo(closed)
        assertThat(days.completeRaceDay(completion)).isEqualTo(completed)
        assertThat(accountRpc.cashOut(request)).isEqualTo(ack)
        val page=accountRpc.listAccountMovements(AccountMovementsRequest.newBuilder().setAccount(aq(f)).setPageSize(1).build())
        assertThat(page.movementsList.single().kind).isEqualTo(AccountMovementKind.FINAL_WITHDRAWAL)
        val last=accountRpc.listAccountMovements(AccountMovementsRequest.newBuilder().setAccount(aq(f)).setPageSize(1).setAfterSequence(page.nextSequence).build())
        assertThat(last.movementsList.single().kind).isEqualTo(AccountMovementKind.PROMOTIONAL_FORFEITURE)
        assertThat(last.hasNextSequence()).isFalse()
        org.springframework.boot.builder.SpringApplicationBuilder(TiketBackendApplication::class.java).run(
            "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}", "--spring.grpc.server.port=0", "--tiket.printing.enabled=false"
        ).use { restarted ->
            val service=restarted.getBean(AccountApplicationService::class.java)
            assertThat(service.cashOut(request)).isEqualTo(ack)
            assertThat(service.receipt(aq(f))).isEqualTo(ack)
            assertThat(restarted.getBean(RaceDayApplicationService::class.java).get(DayQuery.newBuilder().setRaceDayId(f.day).build()).status).isEqualTo(closed)
        }
    }
    @Test fun zeroAccountsRequireExplicitTerminalPair() {
        for (promotional in listOf(0L,100L)) {
            val f=fixture(balance=0,promotional=promotional,open=false)
            races.voidRace(raceCommand(f,0)); complete(f)
            status(Status.Code.FAILED_PRECONDITION) { days.closeRaceDay(dc(f,4)) }
            val receipt=accountRpc.cashOut(cash(f))
            assertThat(receipt.withdrawn).isZero()
            assertThat(receipt.promotionalForfeiture).isEqualTo(promotional)
            assertThat(account(f).movements).hasSize(2)
            days.closeRaceDay(dc(f,4))
        }
    }
    @Test fun everyNonterminalRaceBlocksCompletionAndFailedKeyCanRetry() {
        for(stage in 0..4) {
            val f=fixture(open=stage>0)
            if(stage>1) races.closeBetting(raceCommand(f,1))
            var proposal: SubmitResultRequest?=null
            if(stage>2) { proposal=proposal(f,2); resultRpc.submitResult(proposal) }
            if(stage>3) resultRpc.verifyResult(verify(f,proposal!!.revision,3))
            if(stage==1) {
                status(Status.Code.FAILED_PRECONDITION) { days.finalizeRaceDay(dc(f,2)) }
                races.closeBetting(raceCommand(f,1))
            }
            days.finalizeRaceDay(dc(f,2))
            val request=dc(f,3)
            status(Status.Code.FAILED_PRECONDITION) { days.completeRaceDay(request) }
            if(stage==4) resultRpc.settleRace(settlement(f,4).toBuilder().setCommand(raceCommand(f,4).toBuilder().setExpectedDayVersion(3)).build())
            else races.voidRace(raceCommand(f,when(stage) {0->0; 1,2->2; else->3}).toBuilder().setExpectedDayVersion(3).build())
            assertThat(days.completeRaceDay(request).phase).isEqualTo(DayPhase.COMPLETED)
        }
    }
    @Test fun settledPoolsAndPromotionalLossUseCoreAccounting() {
        val f=fixture(balance=0,promotional=100)
        bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,5),3))
        resultRpc.settleRace(settlement(f,official(f)))
        complete(f)
        val q=accountRpc.getWithdrawalQuote(aq(f))
        assertThat(q.account.balance).isEqualTo(70)
        assertThat(q.withdrawable).isEqualTo(30); assertThat(q.forfeitable).isEqualTo(40)
        val before=account(f)
        val receipt=accountRpc.cashOut(cash(f))
        assertThat(receipt.accountVersion).isEqualTo(before.version+1)
        assertThat(account(f).acceptedStakes).isEqualTo(before.acceptedStakes)
        days.closeRaceDay(dc(f,4))
    }
    @Test fun cashoutAndDayUpdatesRollBackWithTheirIdempotencyReservation() {
        val f=fixture(open=false)
        races.voidRace(raceCommand(f,0)); days.finalizeRaceDay(dc(f,2))
        val completion=dc(f,3)
        assertThatThrownBy { tx.required {
            dayApp.transition(completion,pl.umamusume.tiket.core.raceday.RaceDayPhase.COMPLETED)
            error("rollback completion")
        } }.isInstanceOf(IllegalStateException::class.java)
        days.completeRaceDay(completion)
        val request=cash(f)
        assertThatThrownBy { tx.required { accountApp.cashOut(request); error("rollback cashout") } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(account(f).movements).isEmpty()
        accountRpc.cashOut(request)
        val close=dc(f,4)
        assertThatThrownBy { tx.required {
            dayApp.transition(close,pl.umamusume.tiket.core.raceday.RaceDayPhase.CLOSED); error("rollback closure")
        } }.isInstanceOf(IllegalStateException::class.java)
        days.closeRaceDay(close)
    }
    fun compete(work: (Int)->Any): List<Any> {
        val ready=CountDownLatch(2); val go=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val futures=(0..1).map { n -> pool.submit<Any> { ready.countDown(); go.await()
                try { work(n) } catch(t: Throwable) { t } } }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown()
            return futures.map { it.get(30,TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    @Test fun completionWaitsForFinalVoidOrSettlementOnIndependentConnection() {
        for(voided in listOf(true,false)) {
            val f=fixture(open=!voided)
            val raceVersion=if(voided) 0L else official(f)
            days.finalizeRaceDay(dc(f,2))
            val completion=dc(f,3)
            val executor=Executors.newSingleThreadExecutor(); val started=CountDownLatch(1)
            try {
                lateinit var future: Future<DayAcknowledgement>
                tx.required {
                    jdbc.queryForList("SELECT * FROM race_day_state WHERE race_day_id=? FOR UPDATE",UUID.fromString(f.day))
                    future=executor.submit<DayAcknowledgement> {
                        started.countDown()
                        dayApp.transition(completion,pl.umamusume.tiket.core.raceday.RaceDayPhase.COMPLETED)
                    }
                    assertThat(started.await(5,TimeUnit.SECONDS)).isTrue()
                    val command=raceCommand(f,raceVersion).toBuilder().setExpectedDayVersion(3).build()
                    if(voided) financial.voidRace(command)
                    else financial.settle(SettleRaceRequest.newBuilder().setCommand(command).setSettlementId(id()).build())
                    Unit
                }
                assertThat(future.get(30,TimeUnit.SECONDS).phase).isEqualTo(DayPhase.COMPLETED)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun closureWaitsForLastCashoutAndRetryWorksAfterOutstandingRejection() {
        val f=fixture(open=false); races.voidRace(raceCommand(f,0)); complete(f)
        val close=dc(f,4)
        status(Status.Code.FAILED_PRECONDITION) { days.closeRaceDay(close) }
        val request=cash(f)
        val executor=Executors.newSingleThreadExecutor(); val started=CountDownLatch(1)
        try {
            lateinit var future: Future<DayAcknowledgement>
            tx.required {
                jdbc.queryForList("SELECT * FROM race_day_state WHERE race_day_id=? FOR UPDATE",UUID.fromString(f.day))
                future=executor.submit<DayAcknowledgement> {
                    started.countDown()
                    dayApp.transition(close,pl.umamusume.tiket.core.raceday.RaceDayPhase.CLOSED)
                }
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue()
                accountApp.cashOut(request)
                Unit
            }
            assertThat(future.get(30,TimeUnit.SECONDS).phase).isEqualTo(DayPhase.CLOSED)
            assertThat(accountRpc.cashOut(request)).isEqualTo(accountRpc.getCashoutReceipt(aq(f)))
        } finally { executor.shutdownNow() }
    }
    @Test fun matchingAndDistinctConcurrentCashoutsAndClosures() {
        for(same in listOf(true,false)) {
            val f=fixture(open=false); races.voidRace(raceCommand(f,0)); complete(f)
            val first=cash(f); val second=if(same) first else first.toBuilder().setIdempotencyKey(id()).build()
            val outcomes=compete { accountApp.cashOut(if(it==0) first else second) }
            assertThat(outcomes.filterIsInstance<CashoutReceipt>()).hasSize(if(same) 2 else 1)
            if(same) assertThat(outcomes[0]).isEqualTo(outcomes[1])
            assertThat(account(f).movements).hasSize(2)
            val close=dc(f,4)
            val closures=compete { dayApp.transition(if(it==0 || same) close else close.toBuilder().setIdempotencyKey(id()).build(),
                pl.umamusume.tiket.core.raceday.RaceDayPhase.CLOSED) }
            if(same) assertThat(closures[0]).isEqualTo(closures[1])
            else assertThat(closures.filterIsInstance<DayAcknowledgement>()).hasSize(1)
        }
    }
    @Test fun mixedTwoRaceMultiplePlayerLifecycleAndIndependentPrinting() {
        val f=fixture(balance=1000,promotional=100,running=false)
        // A second immutable definition must be supplied at creation, so create a new event from its configuration.
        val source=days.getRaceDay(DayQuery.newBuilder().setRaceDayId(f.day).build()).definition
        val firstRace=id(); val secondRace=id(); val day=id(); val other=id()
        val config=source.toBuilder().setRaceDayId(day).clearRaces()
            .addRaces(source.racesList.single().toBuilder().setRaceId(firstRace))
            .addRaces(source.racesList.single().toBuilder()
            .setRaceId(secondRace).setNumber(2)).build()
        days.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(config).setIdempotencyKey(id()).build())
        val first=f.copy(day=day,race=firstRace)
        for(player in listOf(first.player,other)) players.enrollPlayer(EnrollPlayerRequest.newBuilder()
            .setRaceDayId(day).setPlayerId(player).setOpeningPaid(1000).setOpeningPromotional(100).setIdempotencyKey(id()).build())
        days.publishRaceDay(dc(first,0)); days.startRaceDay(dc(first,1)); races.openBetting(raceCommand(first,0))
        for(player in listOf(first.player,other)) {
            for(type in listOf(PoolType.WIN,PoolType.QUINELLA,PoolType.EXACTA,PoolType.TRIFECTA))
                bets.purchaseCoupon(purchase(first,type,box(first,1,2,3),1).toBuilder().setPlayerId(player).build())
            bets.purchaseCoupon(purchase(first,PoolType.EXACTA,positional(first,1,0),1).toBuilder().setPlayerId(player).build())
        }
        races.scratchHorse(scratch(first,5,1))
        resultRpc.settleRace(settlement(first,official(first,2)))
        val second=first.copy(race=secondRace)
        races.openBetting(raceCommand(second,0))
        bets.purchaseCoupon(purchase(second,PoolType.WIN,positional(second,1),1))
        races.voidRace(raceCommand(second,1))
        complete(first)
        for(player in listOf(first.player,other)) {
            val current=first.copy(player=player)
            val before=account(current); val q=accountRpc.getWithdrawalQuote(aq(current))
            assertThat(q.withdrawable+q.forfeitable).isEqualTo(before.balance.amount)
            assertThat(q.account.qualifyingTurnover).isEqualTo(before.acceptedStakes.amount-before.refundedStakes.amount)
            val ack=accountRpc.cashOut(cash(current))
            assertThat(ack.withdrawn).isEqualTo(q.withdrawable)
            assertThat(ack.promotionalForfeiture).isEqualTo(q.forfeitable)
        }
        days.closeRaceDay(dc(first,4))
        assertThat(PrintWorker(outbox,pl.umamusume.tiket.printing.console.ConsoleTicketPrinter()).dispatchOne()).isTrue()
        status(Status.Code.FAILED_PRECONDITION) { races.voidRace(raceCommand(second,2).toBuilder().setExpectedDayVersion(5).build()) }
        val receipts=listOf(first.player,other).associateWith { accountRpc.getCashoutReceipt(aq(first.copy(player=it))) }
        org.springframework.boot.builder.SpringApplicationBuilder(TiketBackendApplication::class.java).run(
            "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}", "--spring.grpc.server.port=0", "--tiket.printing.enabled=false"
        ).use { restarted ->
            val service=restarted.getBean(AccountApplicationService::class.java)
            receipts.forEach { (player, receipt) ->
                assertThat(service.receipt(aq(first.copy(player=player)))).isEqualTo(receipt)
                assertThat(service.get(aq(first.copy(player=player))).balance).isZero()
            }
            val dayService=restarted.getBean(RaceDayApplicationService::class.java)
            assertThat(dayService.get(DayQuery.newBuilder().setRaceDayId(day).build()).status.phase).isEqualTo(DayPhase.CLOSED)
            assertThat(restarted.getBean(SettlementRepository::class.java).forRace(
                RaceDayId(UUID.fromString(day)),RaceId(UUID.fromString(firstRace)))).hasSize(1)
        }
    }
    @Test fun emptyEnrollmentCanCloseAfterAllRacesVoided() {
        val source=fixture(running=false)
        val config=days.getRaceDay(DayQuery.newBuilder().setRaceDayId(source.day).build()).definition
        val f=source.copy(day=id(),race=id())
        days.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(config.toBuilder().setRaceDayId(f.day)
            .clearRaces().addRaces(config.racesList.single().toBuilder().setRaceId(f.race))).setIdempotencyKey(id()).build())
        days.publishRaceDay(dc(f,0)); days.startRaceDay(dc(f,1))
        races.voidRace(raceCommand(f,0)); complete(f)
        assertThat(days.closeRaceDay(dc(f,4)).phase).isEqualTo(DayPhase.CLOSED)
    }
    @Test fun terminalInsertFailureRollsBackProjectionFirstMovementAndReservation() {
        val f=fixture(open=false); races.voidRace(raceCommand(f,0)); complete(f)
        val request=cash(f)
        tx.required {
            jdbc.execute("""CREATE FUNCTION phase6_fail_forfeiture() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.movement_type='PROMOTIONAL_FORFEITURE' THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$""")
            jdbc.execute("CREATE TRIGGER phase6_fail BEFORE INSERT ON account_movement FOR EACH ROW EXECUTE FUNCTION phase6_fail_forfeiture()")
        }
        try {
            status(Status.Code.INTERNAL) { accountRpc.cashOut(request) }
            assertThat(account(f).version).isZero()
            assertThat(account(f).movements).isEmpty()
        } finally {
            jdbc.execute("DROP TRIGGER phase6_fail ON account_movement")
            jdbc.execute("DROP FUNCTION phase6_fail_forfeiture()")
        }
        accountRpc.cashOut(request)
    }
    @Test fun completionRejectsMissingAndCorruptTerminalEvidence() {
        for(voided in listOf(true,false)) {
            val f=fixture(); bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,5)))
            if(voided) races.voidRace(raceCommand(f,1)) else resultRpc.settleRace(settlement(f,official(f)))
            days.finalizeRaceDay(dc(f,2))
            for(table in listOf("leg_resolution","pool_state")) {
                assertThatThrownBy { tx.required {
                    if(table=="leg_resolution") {
                        jdbc.execute("ALTER TABLE leg_resolution DISABLE TRIGGER immutable_leg_resolution")
                        jdbc.update("DELETE FROM leg_resolution WHERE coupon_id IN (SELECT id FROM coupon WHERE race_day_id=?)",UUID.fromString(f.day))
                    } else jdbc.update("UPDATE pool_state SET valid_stake=valid_stake+10,active_units=active_units+1 WHERE race_id=? AND bet_type='WIN'",UUID.fromString(f.race))
                    assertThatThrownBy { dayApp.transition(dc(f,3),pl.umamusume.tiket.core.raceday.RaceDayPhase.COMPLETED) }
                        .isInstanceOfAny(CommandPrecondition::class.java,InvalidPersistenceException::class.java)
                    error("undo fixture corruption")
                } }.isInstanceOf(IllegalStateException::class.java)
            }
            days.completeRaceDay(dc(f,3))
        }
    }
    @Test fun missingPoolSettlementAndMissingTerminalMovementBlockFinalization() {
        val f=fixture()
        resultRpc.settleRace(settlement(f,official(f)))
        days.finalizeRaceDay(dc(f,2))
        assertThatThrownBy { tx.required {
            jdbc.execute("ALTER TABLE pool_settlement DISABLE TRIGGER immutable_pool_settlement")
            jdbc.update("DELETE FROM pool_settlement WHERE race_id=? AND bet_type='WIN'",UUID.fromString(f.race))
            assertThatThrownBy { dayApp.transition(dc(f,3),pl.umamusume.tiket.core.raceday.RaceDayPhase.COMPLETED) }
                .isInstanceOf(InvalidPersistenceException::class.java)
            error("rollback incomplete settlement")
        } }.isInstanceOf(IllegalStateException::class.java)
        days.completeRaceDay(dc(f,3)); accountRpc.cashOut(cash(f))
        assertThatThrownBy { tx.required {
            jdbc.execute("ALTER TABLE account_movement DISABLE TRIGGER immutable_account_movement")
            jdbc.update("DELETE FROM account_movement WHERE race_day_id=? AND movement_type='PROMOTIONAL_FORFEITURE'",UUID.fromString(f.day))
            assertThatThrownBy { dayApp.transition(dc(f,4),pl.umamusume.tiket.core.raceday.RaceDayPhase.CLOSED) }
                .isInstanceOf(InvalidPersistenceException::class.java)
            error("rollback missing terminal movement")
        } }.isInstanceOf(IllegalStateException::class.java)
        days.closeRaceDay(dc(f,4))
    }
    @Test fun fullyLostZeroBalanceStillRequiresExplicitCashout() {
        val f=fixture(balance=0,promotional=100)
        bets.purchaseCoupon(purchase(f,PoolType.WIN,positional(f,5),10))
        resultRpc.settleRace(settlement(f,official(f))); complete(f)
        assertThat(accountRpc.getAccount(aq(f)).balance).isZero()
        status(Status.Code.FAILED_PRECONDITION) { days.closeRaceDay(dc(f,4)) }
        val receipt=accountRpc.cashOut(cash(f))
        assertThat(receipt.withdrawn).isZero(); assertThat(receipt.promotionalForfeiture).isZero()
        days.closeRaceDay(dc(f,4))
    }
    @Test fun corruptionAndStaleVersionsRejectFinalOperations() {
        val f=fixture(open=false); races.voidRace(raceCommand(f,0)); complete(f)
        val request=cash(f)
        status(Status.Code.ABORTED) { accountRpc.cashOut(request.toBuilder().setExpectedDayVersion(3).build()) }
        status(Status.Code.ABORTED) { accountRpc.cashOut(request.toBuilder().setExpectedAccountVersion(1).build()) }
        accountRpc.cashOut(request)
        assertThatThrownBy { tx.required {
            jdbc.update("UPDATE account_balance SET version=version+1 WHERE race_day_id=?",UUID.fromString(f.day))
            assertThatThrownBy { dayApp.transition(dc(f,4),pl.umamusume.tiket.core.raceday.RaceDayPhase.CLOSED) }.isInstanceOf(InvalidPersistenceException::class.java)
            error("undo corruption")
        } }.isInstanceOf(IllegalStateException::class.java)
        days.closeRaceDay(dc(f,4))
    }
}
