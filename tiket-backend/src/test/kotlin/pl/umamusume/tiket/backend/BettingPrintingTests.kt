package pl.umamusume.tiket.backend

import io.grpc.*
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
        PrintingServiceGrpc.PrintingServiceBlockingStub::class]
)
class BettingPrintingTests {
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

    fun fixture(balance: Long = 10000, limit: Int = 10000, running: Boolean = true, open: Boolean = true, horseCount: Int = 5): Fixture {
        val f = Fixture(id(), id(), id(), (1..horseCount).map { id() })
        val race =
            RaceConfiguration.newBuilder().setRaceId(f.race).setNumber(1).setTitle("Race").setRacecourseName("Warsaw")
                .addAllRunners(f.horses.mapIndexed { n, h ->
                    Runner.newBuilder().setHorseId(h).setProgramNumber((n + 1).toString()).setDisplayName("Horse")
                        .build()
                })
                .addAllPools(listOf(PoolType.WIN, PoolType.QUINELLA, PoolType.EXACTA, PoolType.TRIFECTA).map {
                    PoolConfiguration.newBuilder().setType(it).setUnitStake(10).setTakeBasisPoints(1500).build()
                }).build()
        val config =
            RaceDayConfiguration.newBuilder().setRaceDayId(f.day).setEventDate("2026-11-10").setDisplayNumber(7)
                .setCurrency("PLN").setTimeZone("Europe/Warsaw").setRulesVersion("v1").setMaximumCouponLegs(limit)
                .setMaximumOpenRaces(1).setNoWinnerPolicy(NoWinnerPolicy.HOUSE_RETAINS_POOL).addRaces(race).build()
        days.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(config).setIdempotencyKey(id()).build())
        players.enrollPlayer(
            EnrollPlayerRequest.newBuilder().setRaceDayId(f.day).setPlayerId(f.player).setOpeningPaid(balance)
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

    @Test fun serialLanesStartTogetherWithoutClaimingBusyPortBacklog() {
        val f = fixture()
        fun buy(pos: Int) = bets.purchaseCoupon(purchase(f).toBuilder().setPointOfSaleNumber(pos).build())
        buy(1); buy(1); buy(2); buy(99)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val seen = ConcurrentHashMap.newKeySet<Int>()
        val routing = pl.umamusume.tiket.printing.serial.SerialRoutes(mapOf(
            1 to pl.umamusume.tiket.printing.serial.SerialPrinterConfiguration("COM9"),
            2 to pl.umamusume.tiket.printing.serial.SerialPrinterConfiguration("COM10")))
        val worker = SerialPrintWorker(outbox, TicketPrinter {
            seen.add(it.pointOfSaleNumber); entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            PrintResult.Dispatched
        }, routing, jdbc, tx)
        try {
            worker.poll()
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue()
            repeat(10) { worker.poll() }
            assertThat(seen).containsExactlyInAnyOrder(1, 2)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM print_job WHERE status='CLAIMED'", Int::class.java)).isEqualTo(2)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM print_job WHERE status='PENDING'", Int::class.java)).isEqualTo(2)
        } finally { release.countDown(); worker.stopClaiming() }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM print_job WHERE status='DISPATCHED'", Int::class.java)).isEqualTo(2)
        assertThat(account(f).balance.amount).isEqualTo(9520)
    }

    @Test fun expiredSerialOwnerCannotCompleteBeforeReclaim() {
        val f = fixture()
        bets.purchaseCoupon(purchase(f).toBuilder().setPointOfSaleNumber(1).build())
        val claim = outbox.claimForPos(setOf(1))!!
        jdbc.update("UPDATE print_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", claim.job)
        SerialLeaseCompletion(outbox, jdbc, tx).complete(claim, PrintResult.Dispatched)
        assertThat(jdbc.queryForObject("SELECT status FROM print_job WHERE id=?", String::class.java, claim.job)).isEqualTo("CLAIMED")
        val retry = outbox.claimForPos(setOf(1))!!
        assertThat(retry.attempt).isNotEqualTo(claim.attempt)
    }

    @Test fun filteredClaimsAreConcurrentAndNeverConsumeOtherPos() {
        val f = fixture()
        val a = bets.purchaseCoupon(purchase(f).toBuilder().setPointOfSaleNumber(1).build())
        val b = bets.purchaseCoupon(purchase(f).toBuilder().setPointOfSaleNumber(2).build())
        assertThat(outbox.claimForPos(setOf(3))).isNull()
        val claims = ConcurrentHashMap<Int, ClaimedPrint>()
        assertThat(compete { index ->
            claims[index + 1] = outbox.claimForPos(setOf(index + 1))!!
        }).containsOnlyNulls()
        assertThat(claims.getValue(1).job.toString()).isEqualTo(a.printJobId)
        assertThat(claims.getValue(2).job.toString()).isEqualTo(b.printJobId)
        assertThat(outbox.claimForPos(setOf(1, 2))).isNull()
        claims.forEach { (pos, claim) ->
            assertThat(outbox.data(claim).pointOfSaleNumber).isEqualTo(pos)
            outbox.complete(claim, PrintResult.Dispatched)
        }
        assertThat(account(f).balance.amount).isEqualTo(9760)
    }

    @ParameterizedTest
    @CsvSource(
        "WIN,1,1", "QUINELLA,1/2,1", "EXACTA,1/2,1", "TRIFECTA,1/2/3,1",
        "EXACTA,1/0,4", "EXACTA,0/1,4", "TRIFECTA,1/0/0,12", "TRIFECTA,0/1/0,12", "TRIFECTA,0/0/1,12",
        "TRIFECTA,1/0/3,3", "QUINELLA,1/0,4", "QUINELLA,0/1,4",
        "WIN,#,3", "QUINELLA,#,3", "EXACTA,#,6", "TRIFECTA,#,6"
    )
    fun quoteExpansion(type: PoolType, expression: String, k: Int) {
        val f = fixture();
        val s = if (expression == "#") box(f, 2, 3, 4) else positional(
            f,
            *expression.split("/").map { it.toInt() }.toIntArray()
        )
        val q = bets.quoteCoupon(quote(f, type, s))
        assertThat(q.pricing.combinationCount).isEqualTo(k); assertThat(q.pricing.totalUnits).isEqualTo(k * 2L)
        assertThat(q.pricing.totalStake).isEqualTo(k * 20L); assertThat(account(f).balance.amount).isEqualTo(10000)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coupon WHERE race_day_id=?",
                Long::class.java,
                UUID.fromString(f.day)
            )
        ).isZero()
    }

    @Test
    fun invalidQuotesAndLimit() {
        val f = fixture()
        listOf(positional(f, 0, 0), positional(f, 1, 1), box(f, 1, 1)).forEach {
            status(Status.Code.INVALID_ARGUMENT) { bets.quoteCoupon(quote(f, PoolType.EXACTA, it)) }
        }
        val unknown =
            BettingSelection.newBuilder().setBox(BoxSelection.newBuilder().addHorseIds(id()).addHorseIds(f.horses[0]))
                .build()
        status(Status.Code.INVALID_ARGUMENT) { bets.quoteCoupon(quote(f, PoolType.WIN, unknown)) }
        listOf(0L, -1L, Long.MAX_VALUE).forEach { n ->
            status(Status.Code.INVALID_ARGUMENT) {
                bets.quoteCoupon(
                    quote(
                        f,
                        PoolType.WIN,
                        positional(f, 1),
                        n
                    )
                )
            }
        }
        val small = fixture(limit = 5)
        status(Status.Code.RESOURCE_EXHAUSTED) { bets.quoteCoupon(quote(small, PoolType.EXACTA, box(small, 2, 3, 4))) }
        jdbc.update(
            "INSERT INTO race_scratch VALUES (?,?,clock_timestamp())",
            UUID.fromString(f.race),
            UUID.fromString(f.horses[0])
        )
        status(Status.Code.INVALID_ARGUMENT) { bets.quoteCoupon(quote(f, PoolType.WIN, positional(f, 1))) }
    }

    @Test
    fun reversedQuinellaQuoteIsIdentical() {
        val f = fixture()
        assertThat(bets.quoteCoupon(quote(f, PoolType.QUINELLA, positional(f, 1, 0))))
            .isEqualTo(bets.quoteCoupon(quote(f, PoolType.QUINELLA, positional(f, 0, 1))))
    }

    @Test
    fun completeBoxPurchaseRetrievalAndConsoleDispatch() {
        val f = fixture();
        val r = purchase(f);
        val ack = bets.purchaseCoupon(r)
        assertThat(ack.pricing.combinationCount).isEqualTo(6); assertThat(ack.pricing.totalUnits).isEqualTo(12)
        assertThat(ack.pricing.totalStake).isEqualTo(120); assertThat(ack.balance).isEqualTo(9880)
        val c = bets.getCoupon(CouponQuery.newBuilder().setRaceDayId(f.day).setCouponId(r.couponId).build())
        assertThat(c.legsList).hasSize(6); assertThat(c.legsList.map { it.stake }).containsOnly(20L)
        assertThat(c.purchasedAt).isEqualTo(ack.purchasedAt); assertThat(c.pointOfSaleNumber).isEqualTo(12)
        assertThat(account(f).qualifyingTurnover.amount).isEqualTo(120)
        val pool =
            jdbc.queryForMap("SELECT * FROM pool_state WHERE race_id=? AND bet_type='EXACTA'", UUID.fromString(f.race))
        assertThat(pool["valid_stake"]).isEqualTo(120L); assertThat(pool["active_units"]).isEqualTo(12L)
        assertThat(
            jdbc.queryForObject(
                "SELECT sum(valid_stake) FROM pool_state WHERE race_id=? AND bet_type<>'EXACTA'",
                Long::class.java,
                UUID.fromString(f.race)
            )
        ).isZero()
        val listing = bets.listPlayerCoupons(
            ListPlayerCouponsRequest.newBuilder().setRaceDayId(f.day).setPlayerId(f.player).setPageSize(1).build()
        )
        assertThat(listing.couponsList).containsExactly(c)
        val claim = outbox.claim()!!;
        val data = outbox.data(claim)
        assertThat(data.raceDayNumber).isEqualTo("007"); assertThat(data.betString).isEqualTo("#2,3,4")
        assertThat(data.cashierIdentifier).isEqualTo("cashier"); assertThat(data.pointOfSaleNumber).isEqualTo(12)
        assertThat(data.betDateTime).isEqualTo(
            LocalDateTime.ofInstant(
                Instant.parse(ack.purchasedAt),
                ZoneId.of("Europe/Warsaw")
            ).withSecond(0).withNano(0)
        )
        val output = ByteArrayOutputStream()
        val result = pl.umamusume.tiket.printing.console.ConsoleTicketPrinter(PrintStream(output)).print(data)
        assertThat(output.toString()).contains(
            "007",
            "#2,3,4",
            "120 PLN",
            "Units per combination: 2",
            "Print attempt:",
            "Console dispatch only"
        )
        outbox.complete(claim, result)
        assertThat(
            prints.getPrintStatus(
                PrintStatusQuery.newBuilder().setPrintJobId(ack.printJobId).build()
            ).status
        ).isEqualTo(DispatchStatus.DISPATCHED)
    }

    @Test
    fun ordinaryAndWallPurchasesIncrementExistingSelections() {
        val f = fixture()
        bets.purchaseCoupon(purchase(f, PoolType.WIN, positional(f, 1)))
        bets.purchaseCoupon(purchase(f, PoolType.WIN, positional(f, 1)))
        bets.purchaseCoupon(purchase(f, PoolType.EXACTA, positional(f, 1, 0)))
        assertThat(account(f).balance.amount).isEqualTo(9880)
        assertThat(count("coupon_leg", f)).isEqualTo(6)
        assertThat(
            jdbc.queryForObject(
                "SELECT units FROM pool_selection_units WHERE race_id=? AND bet_type='WIN'",
                Long::class.java,
                UUID.fromString(f.race)
            )
        ).isEqualTo(4)
    }

    @Test
    fun preconditionsVersionsAndDuplicateIdentity() {
        val f = fixture(balance = 100)
        status(Status.Code.FAILED_PRECONDITION) { bets.purchaseCoupon(purchase(f)) }
        val r = purchase(f, PoolType.WIN, positional(f, 1))
        status(Status.Code.ABORTED) { bets.purchaseCoupon(r.toBuilder().setExpectedRaceVersion(0).build()) }
        status(Status.Code.ABORTED) { bets.purchaseCoupon(r.toBuilder().setExpectedAccountVersion(1).build()) }
        status(Status.Code.FAILED_PRECONDITION) {
            bets.purchaseCoupon(
                r.toBuilder().setQuoteConfirmation("0".repeat(64)).build()
            )
        }
        bets.purchaseCoupon(r)
        status(Status.Code.ALREADY_EXISTS) { bets.purchaseCoupon(r.toBuilder().setIdempotencyKey(id()).build()) }
        races.closeBetting(raceCommand(f, 1))
        status(Status.Code.FAILED_PRECONDITION) { bets.quoteCoupon(quote(f, PoolType.WIN, positional(f, 1))) }
        assertThat(bets.purchaseCoupon(r).balance).isEqualTo(80)
        val draft = fixture(running = false)
        status(Status.Code.FAILED_PRECONDITION) { bets.quoteCoupon(quote(draft, PoolType.WIN, positional(draft, 1))) }
    }

    fun compete(work: (Int) -> Unit): List<Throwable?> {
        val barrier = CyclicBarrier(2);
        val executor = Executors.newFixedThreadPool(2)
        try {
            return (0..1).map { i ->
                executor.submit<Throwable?> {
                    try {
                        tx.required {
                            jdbc.queryForObject("SELECT 1", Int::class.java); barrier.await(
                            15,
                            TimeUnit.SECONDS
                        ); work(i); Unit
                        }; null
                    } catch (t: Throwable) {
                        t
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentAccountCannotOverdrawAndPoolDoesNotLoseContributions() {
        val f = fixture(balance = 150);
        val requests = (0..1).map { purchase(f) }
        assertThat(compete { app.purchase(requests[it]) }.count { it == null }).isEqualTo(1)
        assertThat(account(f).balance.amount).isEqualTo(30)
        val g = fixture();
        val rs = (0..1).map { purchase(g) }
        assertThat(compete { app.purchase(rs[it]) }).containsOnlyNulls()
        assertThat(
            jdbc.queryForObject(
                "SELECT valid_stake FROM pool_state WHERE race_id=? AND bet_type='EXACTA'",
                Long::class.java,
                UUID.fromString(g.race)
            )
        ).isEqualTo(240)
    }

    @Test
    fun concurrentSameKeyAndReplayAfterAnotherDebit() {
        val f = fixture();
        val r = purchase(f)
        assertThat(compete { app.purchase(r) }).containsOnlyNulls()
        val ack = bets.purchaseCoupon(r)
        bets.purchaseCoupon(purchase(f, PoolType.WIN, positional(f, 1)))
        assertThat(bets.purchaseCoupon(r)).isEqualTo(ack)
        status(Status.Code.ALREADY_EXISTS) { bets.purchaseCoupon(r.toBuilder().setCashierIdentifier("other").build()) }
        assertThat(account(f).movements).hasSize(2)
    }

    @Test
    fun rollbackIncludesPrintJobAndAllowsRetry() {
        val f = fixture();
        val r = purchase(f)
        assertThatThrownBy { tx.required { app.purchase(r); error("injected failure") } }.isInstanceOf(
            IllegalStateException::class.java
        )
        assertThat(account(f).balance.amount).isEqualTo(10000); assertThat(count("coupon_leg", f)).isZero()
        assertThat(count("print_job", f)).isZero()
        assertThat(
            jdbc.queryForObject(
                "SELECT sum(valid_stake) FROM pool_state WHERE race_id=?",
                Long::class.java,
                UUID.fromString(f.race)
            )
        ).isZero()
        bets.purchaseCoupon(r)
    }

    @Test
    fun closeWinningLockRejectsPurchaseAndPurchaseWinningLockRemainsValid() {
        for (closeFirst in listOf(true, false)) {
            val f = fixture();
            val r = purchase(f);
            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                lateinit var future: Future<Throwable?>
                tx.required {
                    jdbc.queryForList(
                        "SELECT * FROM race_day_state WHERE race_day_id=? FOR UPDATE",
                        UUID.fromString(f.day)
                    )
                    future = executor.submit<Throwable?> {
                        started.countDown()
                        try {
                            if (closeFirst) app.purchase(r) else raceApp.betting(raceCommand(f, 1), false); null
                        } catch (t: Throwable) {
                            t
                        }
                    }
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
                    if (closeFirst) raceApp.betting(raceCommand(f, 1), false) else app.purchase(r)
                    Unit
                }
                if (closeFirst) assertThat(
                    future.get(
                        20,
                        TimeUnit.SECONDS
                    )
                ).isInstanceOf(StaleVersionException::class.java)
                else {
                    assertThat(
                        future.get(
                            20,
                            TimeUnit.SECONDS
                        )
                    ).isNull(); assertThat(account(f).balance.amount).isEqualTo(9880)
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun workerFailureRetryReprintAndLeaseRecovery() {
        val f = fixture();
        val ack = bets.purchaseCoupon(purchase(f))
        var calls = 0;
        val records = mutableListOf<TicketPrintData>()
        val worker = PrintWorker(
            outbox,
            TicketPrinter { calls++; records.add(it); if (calls == 1) error("offline") else PrintResult.Dispatched })
        worker.dispatchOne()
        val query = PrintStatusQuery.newBuilder().setPrintJobId(ack.printJobId).build()
        assertThat(prints.getPrintStatus(query).status).isEqualTo(DispatchStatus.RETRYING)
        jdbc.update(
            "UPDATE print_job SET next_attempt_at=clock_timestamp() WHERE id=?",
            UUID.fromString(ack.printJobId)
        )
        worker.dispatchOne()
        assertThat(prints.getPrintStatus(query).status).isEqualTo(DispatchStatus.DISPATCHED)
        assertThat(records[1].printAttemptId).isNotEqualTo(records[0].printAttemptId)
        val reprint =
            ReprintCouponRequest.newBuilder().setRaceDayId(f.day).setCouponId(ack.couponId).setIdempotencyKey(id())
                .build()
        val job = prints.reprintCoupon(reprint)
        assertThat(prints.reprintCoupon(reprint)).isEqualTo(job)
        val claim = outbox.claim()!!
        val data = outbox.data(claim)
        assertThat(data.isReprint).isTrue(); assertThat(data.betDateTime).isEqualTo(records[0].betDateTime)
        assertThat(outbox.claim()).isNull()
        jdbc.update("UPDATE print_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", claim.job)
        val recovered = outbox.claim()!!
        assertThat(recovered.job).isEqualTo(claim.job); assertThat(recovered.attempt).isNotEqualTo(claim.attempt)
        outbox.complete(claim, PrintResult.Dispatched) // stale owner cannot complete recovered job
        assertThat(outbox.status(PrintStatusQuery.newBuilder().setPrintJobId(job.printJobId).build()).status).isEqualTo(
            DispatchStatus.CLAIMED
        )
        outbox.complete(recovered, PrintResult.Failed("permanent", false))
        assertThat(outbox.status(PrintStatusQuery.newBuilder().setPrintJobId(job.printJobId).build()).status).isEqualTo(
            DispatchStatus.PERMANENTLY_FAILED
        )
        assertThat(account(f).balance.amount).isEqualTo(9880)
    }

    @Test
    fun noDispatchBeforeCommitAndCompetingClaims() {
        val f = fixture();
        val r = purchase(f)
        val executor = Executors.newSingleThreadExecutor()
        try {
            tx.required {
                app.purchase(r)
                assertThat(executor.submit<ClaimedPrint?> { outbox.claim() }.get(10, TimeUnit.SECONDS)).isNull()
                Unit
            }
            val claims = java.util.Collections.synchronizedList(mutableListOf<ClaimedPrint?>())
            assertThat(compete { claims.add(outbox.claim()) }).containsOnlyNulls()
            assertThat(claims.count { it != null }).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun restartPreservesPendingJobAndExactPurchaseAcknowledgement() {
        val f = fixture();
        val r = purchase(f);
        val ack = bets.purchaseCoupon(r)
        org.springframework.boot.builder.SpringApplicationBuilder(TiketBackendApplication::class.java).run(
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.grpc.server.port=0",
            "--tiket.printing.enabled=false"
        ).use { restarted ->
            assertThat(restarted.getBean(BettingApplicationService::class.java).purchase(r)).isEqualTo(ack)
            val fresh = restarted.getBean(PrintOutbox::class.java)
            val c = fresh.claim()!!
            assertThat(c.job.toString()).isEqualTo(ack.printJobId)
            fresh.complete(c, PrintResult.Dispatched)
        }
    }

    @ParameterizedTest
    @CsvSource("QUINELLA,1/2", "TRIFECTA,1/2/3")
    fun otherPoolPurchases(type: PoolType, expression: String) {
        val f = fixture()
        val s = positional(f, *expression.split("/").map { it.toInt() }.toIntArray())
        val ack = bets.purchaseCoupon(purchase(f, type, s))
        assertThat(ack.balance).isEqualTo(9980)
        assertThat(
            jdbc.queryForObject(
                "SELECT valid_stake FROM pool_state WHERE race_id=? AND bet_type=?", Long::class.java,
                UUID.fromString(f.race), type.name
            )
        ).isEqualTo(20)
    }

    @Test
    fun eligibilityChangeInvalidatesWallConfirmationWithoutVersionChange() {
        val f = fixture();
        val r = purchase(f, PoolType.EXACTA, positional(f, 1, 0))
        // Trusted fixture: Phase 5 will own real scratch commands/version changes.
        jdbc.update(
            "INSERT INTO race_scratch VALUES (?,?,clock_timestamp())",
            UUID.fromString(f.race),
            UUID.fromString(f.horses[4])
        )
        status(Status.Code.FAILED_PRECONDITION) { bets.purchaseCoupon(r) }
        assertThat(account(f).balance.amount).isEqualTo(10000)
        assertThat(count("print_job", f)).isZero()
    }

    @Test
    fun pagingAndImmutableReconstructionAfterScratch() {
        val f = fixture()
        val a = bets.purchaseCoupon(purchase(f, PoolType.EXACTA, positional(f, 1, 0)))
        bets.purchaseCoupon(purchase(f, PoolType.WIN, positional(f, 2)))
        val request =
            ListPlayerCouponsRequest.newBuilder().setRaceDayId(f.day).setPlayerId(f.player).setPageSize(1).build()
        val first = bets.listPlayerCoupons(request)
        assertThat(first.hasNextOffset()).isTrue()
        val second = bets.listPlayerCoupons(request.toBuilder().setOffset(first.nextOffset).build())
        assertThat(second.hasNextOffset()).isFalse()
        assertThat(second.getCoupons(0).couponId).isNotEqualTo(first.getCoupons(0).couponId)
        val query = CouponQuery.newBuilder().setRaceDayId(f.day).setCouponId(a.couponId).build()
        val original = bets.getCoupon(query)
        jdbc.update(
            "INSERT INTO race_scratch VALUES (?,?,clock_timestamp())",
            UUID.fromString(f.race),
            UUID.fromString(f.horses[4])
        )
        assertThat(bets.getCoupon(query)).isEqualTo(original)
    }

    @Test
    fun poolOverflowRollsBackEveryFinancialAndPrintWrite() {
        val f = fixture(balance = Long.MAX_VALUE)
        val units = Long.MAX_VALUE / 10
        bets.purchaseCoupon(purchase(f, PoolType.WIN, positional(f, 1), units))
        // A second player has funds, but the target pool cannot accept another ten units of money.
        val player = id()
        // Enrollment cannot occur while running: construct a separate pre-enrolled player through trusted fixtures.
        tx.required {
            val entry = pl.umamusume.tiket.core.accounting.PlayerEntry(
                RaceDayId(UUID.fromString(f.day)), PlayerId(UUID.fromString(player)),
                pl.umamusume.tiket.core.money.Money(100), pl.umamusume.tiket.core.money.Money.ZERO
            )
            PlayerEntryRepository(jdbc, tx).insert(entry)
            Unit
        }
        val r = purchase(f, PoolType.WIN, positional(f, 1), 1).toBuilder().setPlayerId(player).build()
        status(Status.Code.INVALID_ARGUMENT) { bets.purchaseCoupon(r) }
        assertThat(
            accounts.load(
                RaceDayId(UUID.fromString(f.day)),
                PlayerId(UUID.fromString(player))
            )!!.balance.amount
        ).isEqualTo(100)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coupon WHERE id=?",
                Long::class.java,
                UUID.fromString(r.couponId)
            )
        ).isZero()
        assertThat(count("print_job", f)).isEqualTo(1)
    }

    @Test
    fun retryLimitAndPrintAttemptTimeMapping() {
        val f = fixture();
        val ack = bets.purchaseCoupon(purchase(f))
        var first: TicketPrintData? = null
        repeat(5) { n ->
            val claim = outbox.claim()!!
            val data = outbox.data(claim)
            if (n == 0) {
                first = data
                val later = outbox.data(claim.copy(startedAt = claim.startedAt.plusSeconds(120)))
                assertThat(later.printDateTime).isEqualTo(data.printDateTime.plusMinutes(2))
                assertThat(later.betDateTime).isEqualTo(data.betDateTime)
            }
            outbox.complete(claim, PrintResult.Failed("transient", true))
            jdbc.update("UPDATE print_job SET next_attempt_at=clock_timestamp() WHERE id=?", claim.job)
        }
        val status = prints.getPrintStatus(PrintStatusQuery.newBuilder().setPrintJobId(ack.printJobId).build())
        assertThat(status.status).isEqualTo(DispatchStatus.PERMANENTLY_FAILED)
        assertThat(status.attemptCount).isEqualTo(5)
        assertThat(outbox.claim()).isNull()
        assertThat(first!!.totalBetValue).isEqualTo(120)
    }

    @Test fun largeCouponBatchesAllLegsAndEnforcesPreallocationLimit() {
        val f = fixture(balance = 100000, horseCount = 101, limit = 9900)
        status(Status.Code.RESOURCE_EXHAUSTED) {
            bets.quoteCoupon(quote(f, PoolType.EXACTA, box(f, *(1..101).toList().toIntArray()), 1))
        }
        val r = purchase(f, PoolType.EXACTA, box(f, *(1..100).toList().toIntArray()), 1)
        val ack = bets.purchaseCoupon(r)
        assertThat(ack.pricing.combinationCount).isEqualTo(9900)
        assertThat(ack.pricing.totalStake).isEqualTo(99000)
        assertThat(ack.balance).isEqualTo(1000)
        assertThat(count("coupon_leg", f)).isEqualTo(9900)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pool_selection_units WHERE race_id=?",
            Long::class.java, UUID.fromString(f.race))).isEqualTo(9900)
        assertThat(count("print_job", f)).isEqualTo(1)
    }
}
