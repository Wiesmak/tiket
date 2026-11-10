package pl.umamusume.tiket.backend

import io.grpc.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport
import org.springframework.grpc.client.ImportGrpcClients
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.umamusume.tiket.backend.application.*
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.identity.*
import java.util.UUID
import java.util.concurrent.*

@Testcontainers
@SpringBootTest(properties = ["spring.grpc.server.port=0", "spring.grpc.server.address=127.0.0.1"])
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = [RaceDayServiceGrpc.RaceDayServiceBlockingStub::class,
    RaceServiceGrpc.RaceServiceBlockingStub::class, EnrollmentServiceGrpc.EnrollmentServiceBlockingStub::class])
class AdministrationTests {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic @DynamicPropertySource fun database(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
        }
    }
    @Autowired lateinit var days: RaceDayServiceGrpc.RaceDayServiceBlockingStub
    @Autowired lateinit var races: RaceServiceGrpc.RaceServiceBlockingStub
    @Autowired lateinit var players: EnrollmentServiceGrpc.EnrollmentServiceBlockingStub
    @Autowired lateinit var app: RaceDayApplicationService
    @Autowired lateinit var raceApp: RaceApplicationService
    @Autowired lateinit var enrollment: EnrollmentApplicationService
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var entries: PlayerEntryRepository
    @Autowired lateinit var tx: PersistenceTransactions
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var environment: org.springframework.core.env.Environment
    fun id() = UUID.randomUUID().toString()
    fun configuration(maxOpen: Int = 1): RaceDayConfiguration {
        val horse = id()
        return RaceDayConfiguration.newBuilder().setRaceDayId(id()).setEventDate("2026-11-10").setDisplayNumber(42)
            .setCurrency("PLN").setTimeZone("Europe/Warsaw").setRulesVersion("v1").setMaximumCouponLegs(10000)
            .setMaximumOpenRaces(maxOpen).setNoWinnerPolicy(NoWinnerPolicy.HOUSE_RETAINS_POOL)
            .addAllRaces(listOf(9, 2, 7).map { n ->
                RaceConfiguration.newBuilder().setRaceId(id()).setNumber(n).setTitle("Race $n").setRacecourseName("Warsaw")
                    .addAllRunners((1..4).map { Runner.newBuilder().setHorseId(if(it==1) horse else id())
                        .setProgramNumber(it.toString()).setDisplayName("Horse $it").build() })
                    .addAllPools(listOf(PoolType.WIN,PoolType.QUINELLA,PoolType.EXACTA,PoolType.TRIFECTA).mapIndexed { i, type ->
                        PoolConfiguration.newBuilder().setType(type).setUnitStake(100L+i)
                            .setTakeBasisPoints(listOf(0,10000,1234,9876)[i]).build()
                    }).build()
            }).build()
    }
    fun create(c: RaceDayConfiguration = configuration()): RaceDayConfiguration {
        days.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(c).setIdempotencyKey(id()).build())
        return c
    }
    fun query(c: RaceDayConfiguration) = DayQuery.newBuilder().setRaceDayId(c.raceDayId).build()
    fun command(c: RaceDayConfiguration, v: Long, key: String = id()) = DayCommand.newBuilder()
        .setRaceDayId(c.raceDayId).setExpectedVersion(v).setIdempotencyKey(key).build()
    fun enroll(c: RaceDayConfiguration, v: Long = 0, paid: Long = 100, promo: Long = 20) = EnrollPlayerRequest.newBuilder()
        .setRaceDayId(c.raceDayId).setPlayerId(id()).setOpeningPaid(paid).setOpeningPromotional(promo)
        .setExpectedDayVersion(v).setIdempotencyKey(id()).build()
    fun fund(e: EnrollPlayerRequest, v: Long, paid: Long, promo: Long) = AddOpeningFundingRequest.newBuilder()
        .setRaceDayId(e.raceDayId).setPlayerId(e.playerId).setExpectedAccountVersion(v)
        .setAdditionalPaid(paid).setAdditionalPromotional(promo).setIdempotencyKey(id()).build()
    fun betting(c: RaceDayConfiguration, index: Int, v: Long = 0) = RaceCommand.newBuilder()
        .setRaceDayId(c.raceDayId).setRaceId(c.getRaces(index).raceId).setExpectedDayVersion(2)
        .setExpectedRaceVersion(v).setIdempotencyKey(id()).build()
    fun running(c: RaceDayConfiguration) {
        days.publishRaceDay(command(c,0)); days.startRaceDay(command(c,1))
    }
    fun status(code: Status.Code, work: () -> Unit) {
        assertThatThrownBy(work).isInstanceOfSatisfying(StatusRuntimeException::class.java) {
            assertThat(it.status.code).isEqualTo(code)
        }
    }
    @Test fun completeCreationAndExactReplay() {
        val c = create()
        val loaded = days.getRaceDay(query(c))
        assertThat(loaded.definition).isEqualTo(c)
        assertThat(loaded.status.phase).isEqualTo(DayPhase.DRAFT)
        assertThat(races.listRaces(query(c)).racesList.map { it.definition.number }).containsExactly(9,2,7)
        val publish = command(c,0)
        val ack = days.publishRaceDay(publish)
        days.startRaceDay(command(c,1))
        assertThat(days.publishRaceDay(publish)).isEqualTo(ack)
        assertThat(ack.version).isEqualTo(1)
        status(Status.Code.ALREADY_EXISTS) { days.publishRaceDay(publish.toBuilder().setExpectedVersion(1).build()) }
        val other = create()
        status(Status.Code.NOT_FOUND) { races.getRace(RaceQuery.newBuilder().setRaceDayId(other.raceDayId).setRaceId(c.getRaces(0).raceId).build()) }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pool_state WHERE race_id=?", Long::class.java, UUID.fromString(c.getRaces(0).raceId))).isEqualTo(4)
    }
    @Test fun invalidDefinitionsLeaveNoRows() {
        val c = configuration()
        val r = c.getRaces(0)
        val invalid = listOf(
            c.toBuilder().setRaceDayId("1-1-1-1-1").build(),
            c.toBuilder().setEventDate("2026-02-30").build(),
            c.toBuilder().setTimeZone("+02:00").build(),
            c.toBuilder().setNoWinnerPolicyValue(999).build(),
            c.toBuilder().addRaces(r).build(),
            c.toBuilder().setRaces(1,c.getRaces(1).toBuilder().setNumber(9)).build(),
            c.toBuilder().setRaces(0,r.toBuilder().clearPools()).build(),
            c.toBuilder().setRaces(0,r.toBuilder().setPools(0,r.getPools(0).toBuilder().setUnitStake(0))).build(),
            c.toBuilder().setRaces(0,r.toBuilder().setPools(0,r.getPools(0).toBuilder().setTakeBasisPoints(10001))).build(),
            c.toBuilder().setRaces(0,r.toBuilder().addRunners(r.getRunners(0))).build(),
            c.toBuilder().setRaces(0,r.toBuilder().setRunners(1,r.getRunners(1).toBuilder().setProgramNumber("1"))).build()
        )
        invalid.forEach { bad -> status(Status.Code.INVALID_ARGUMENT) { days.createRaceDay(CreateRaceDayRequest.newBuilder()
            .setDefinition(bad).setIdempotencyKey(id()).build()) } }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM race_day WHERE id=?", Long::class.java,UUID.fromString(c.raceDayId))).isZero()
        val existing = create()
        val collision = configuration().toBuilder().setRaces(2,existing.getRaces(0).toBuilder().setNumber(77)).build()
        status(Status.Code.ALREADY_EXISTS) { days.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(collision).setIdempotencyKey(id()).build()) }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM race_day WHERE id=?", Long::class.java,UUID.fromString(collision.raceDayId))).isZero()
    }
    @Test fun fundingReconstructsWithoutMovements() {
        val c = create()
        val e = enroll(c,paid=0,promo=0)
        assertThat(players.enrollPlayer(e).balance).isZero()
        status(Status.Code.ALREADY_EXISTS) { players.enrollPlayer(e.toBuilder().setIdempotencyKey(id()).build()) }
        listOf(10L to 0L,0L to 20L,30L to 40L).forEachIndexed { i, amounts ->
            val request = fund(e,i.toLong(),amounts.first,amounts.second)
            val ack = players.addOpeningFunding(request)
            assertThat(players.addOpeningFunding(request)).isEqualTo(ack)
        }
        val loaded = accounts.load(RaceDayId(UUID.fromString(c.raceDayId)),PlayerId(UUID.fromString(e.playerId)))!!
        assertThat(loaded.version).isEqualTo(3)
        assertThat(loaded.balance.amount).isEqualTo(100)
        assertThat(loaded.movements).isEmpty()
        val freshEntries = PlayerEntryRepository(jdbc,tx)
        val freshAccounts = AccountRepository(jdbc,tx,freshEntries)
        assertThat(freshAccounts.load(loaded.entry.raceDayId,loaded.entry.playerId)!!.balance).isEqualTo(loaded.balance)
        assertThat(players.getEnrollment(EnrollmentQuery.newBuilder().setRaceDayId(c.raceDayId).setPlayerId(e.playerId).build()).openingVersion).isEqualTo(3)
        status(Status.Code.ABORTED) { players.addOpeningFunding(fund(e,0,1,1)) }
        days.publishRaceDay(command(c,0))
        players.enrollPlayer(enroll(c,1))
        days.startRaceDay(command(c,1))
        status(Status.Code.FAILED_PRECONDITION) { players.enrollPlayer(enroll(c,2)) }
        status(Status.Code.FAILED_PRECONDITION) { players.addOpeningFunding(fund(e,3,1,1)) }
        assertThatThrownBy { jdbc.update("INSERT INTO opening_funding_adjustment(race_day_id,player_id,opening_version,added_paid,added_promotional) VALUES (?,?,4,1,0)",
            UUID.fromString(c.raceDayId), UUID.fromString(e.playerId)) }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }
    @Test fun bettingAndFinalization() {
        val c=create(); running(c)
        status(Status.Code.ABORTED) { days.finalizeRaceDay(command(c,1)) }
        races.openBetting(betting(c,0))
        status(Status.Code.FAILED_PRECONDITION) { races.openBetting(betting(c,1)) }
        status(Status.Code.FAILED_PRECONDITION) { days.finalizeRaceDay(command(c,2)) }
        assertThat(races.closeBetting(betting(c,0,1)).version).isEqualTo(2)
        status(Status.Code.FAILED_PRECONDITION) { races.openBetting(betting(c,0,2)) }
        races.openBetting(betting(c,1)); races.closeBetting(betting(c,1,1))
        assertThat(days.finalizeRaceDay(command(c,2)).phase).isEqualTo(DayPhase.FINALIZING)
        status(Status.Code.FAILED_PRECONDITION) { races.openBetting(betting(c,2).toBuilder().setExpectedDayVersion(3).build()) }
        assertThat(races.listRaces(query(c)).getRaces(0).definition.runnersList).isEqualTo(c.getRaces(0).runnersList)
        val two=create(configuration(2)); running(two)
        races.openBetting(betting(two,0)); races.openBetting(betting(two,1))
        status(Status.Code.FAILED_PRECONDITION) { races.openBetting(betting(two,2)) }
    }
    fun compete(work: (Int)->Unit): List<Throwable?> {
        val barrier=CyclicBarrier(2)
        val executor=Executors.newFixedThreadPool(2)
        try {
            return (0..1).map { i -> executor.submit<Throwable?> {
                try { tx.required { jdbc.queryForObject("SELECT 1",Int::class.java)
                    barrier.await(15,TimeUnit.SECONDS); work(i); Unit }; null
                } catch(t: Throwable) { t }
            } }.map { it.get(30,TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }
    @Test fun concurrentOpenAndFundingVersionChecks() {
        val c=create(); running(c)
        assertThat(compete { raceApp.betting(betting(c,it),true) }.count { it==null }).isEqualTo(1)
        val d=create(); val e=enroll(d); players.enrollPlayer(e)
        assertThat(compete { enrollment.fund(fund(e,0,1,1)) }.count { it==null }).isEqualTo(1)
        assertThat(players.listPlayers(query(d)).getPlayers(0).accountVersion).isEqualTo(1)
    }
    @Test fun concurrentStartSerializesEnrollmentAndFunding() {
        repeat(2) { funding ->
            val c=create(); val e=enroll(c); players.enrollPlayer(e); days.publishRaceDay(command(c,0))
            val outcome=compete { i -> if(i==0) app.transition(command(c,1),pl.umamusume.tiket.core.raceday.RaceDayPhase.RUNNING)
                else if(funding==0) enrollment.enroll(enroll(c,1)) else enrollment.fund(fund(e,0,5,5)) }
            assertThat(outcome[0]).isNull()
            assertThat(days.getRaceDay(query(c)).status.phase).isEqualTo(DayPhase.RUNNING)
            status(Status.Code.FAILED_PRECONDITION) { players.addOpeningFunding(fund(e,if(outcome[1]==null && funding==1) 1 else 0,1,0)) }
        }
    }
    @Test fun concurrentIdempotencyAndRollbackRetry() {
        val c=create(); val request=enroll(c)
        assertThat(compete { enrollment.enroll(request) }).containsOnlyNulls()
        assertThat(players.listPlayers(query(c)).playersCount).isEqualTo(1)
        val publish=command(c,0)
        assertThatThrownBy { tx.required { app.transition(publish,pl.umamusume.tiket.core.raceday.RaceDayPhase.READY); error("rollback") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(days.publishRaceDay(publish).version).isEqualTo(1)
        val d=create()
        assertThat(compete { app.transition(command(d,0),pl.umamusume.tiket.core.raceday.RaceDayPhase.READY) }.count { it==null }).isEqualTo(1)
    }
    @Test fun realNettyUsesGeneratedAdministrationStubs() {
        val context=org.springframework.boot.builder.SpringApplicationBuilder(TiketBackendApplication::class.java).run(
            "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}", "--spring.grpc.server.port=0")
        val port=context.environment.getRequiredProperty("local.grpc.server.port",Int::class.java)
        val channel=ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build()
        try {
            val stub=RaceDayServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS)
            val c=configuration()
            assertThat(stub.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(c).setIdempotencyKey(id()).build()).phase).isEqualTo(DayPhase.DRAFT)
            assertThat(stub.getRaceDay(query(c)).definition).isEqualTo(c)
            val e=enroll(c)
            val enrollmentStub=EnrollmentServiceGrpc.newBlockingStub(channel)
            enrollmentStub.enrollPlayer(e)
            val funded=enrollmentStub.addOpeningFunding(fund(e,0,10,20))
            assertThat(funded.openingVersion).isEqualTo(1)
            // A second complete application uses a new pool and repositories against the same durable rows.
            org.springframework.boot.builder.SpringApplicationBuilder(TiketBackendApplication::class.java).run(
                "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}", "--spring.grpc.server.port=0").use { restarted ->
                val loaded=restarted.getBean(AccountRepository::class.java).load(
                    RaceDayId(UUID.fromString(c.raceDayId)), PlayerId(UUID.fromString(e.playerId)))!!
                assertThat(loaded.balance.amount).isEqualTo(funded.balance)
                assertThat(loaded.version).isEqualTo(1)
            }
            status(Status.Code.INVALID_ARGUMENT) { stub.getRaceDay(DayQuery.newBuilder().setRaceDayId("bad").build()) }
            status(Status.Code.NOT_FOUND) { stub.getRaceDay(DayQuery.newBuilder().setRaceDayId(id()).build()) }
        } finally { channel.shutdownNow(); channel.awaitTermination(5,TimeUnit.SECONDS); context.close() }
    }
}
