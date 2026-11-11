package pl.umamusume.tiket.backend

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.*
import io.grpc.*
import io.grpc.stub.MetadataUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.*
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.umamusume.tiket.backend.application.PrintOutbox
import pl.umamusume.tiket.contracts.v1.*
import java.util.*
import java.util.concurrent.*

@Testcontainers
class MultiInstanceSecurityTests {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
    private fun start(): ConfigurableApplicationContext = SpringApplicationBuilder(
        TiketBackendApplication::class.java, FinancialJwtConfiguration::class.java).run(
        "--spring.profiles.active=security-test", "--spring.main.web-application-type=servlet",
        "--server.port=-1", "--management.server.port=0", "--spring.grpc.server.port=0",
        "--spring.datasource.url=" + postgres.jdbcUrl,
        "--spring.datasource.username=" + postgres.username, "--spring.datasource.password=" + postgres.password,
        "--tiket.security.issuer=https://issuer.test", "--tiket.security.jwks=https://issuer.test/keys",
        "--tiket.security.audience=tiket", "--tiket.printing.enabled=false")
    private fun id() = UUID.randomUUID().toString()

    @Test fun sharedDatabaseReplayCashoutAndPrintLeaseRecoveryAcrossTwoSecuredContexts() {
        start().use { first -> start().use { second ->
            val event=id(); val race=id(); val player=id(); val horse=id()
            val channels = listOf(first,second).map {
                ManagedChannelBuilder.forAddress("127.0.0.1",it.environment.getRequiredProperty("local.grpc.server.port",Int::class.java)).usePlaintext().build()
            }
            fun secure(channel: Channel, subject: String="operator"): Channel {
                val token = SignedJWT(JWSHeader(JWSAlgorithm.RS256),JWTClaimsSet.Builder().issuer("https://issuer.test")
                    .audience("tiket").subject(subject).expirationTime(Date(System.currentTimeMillis()+300000))
                    .claim("tiket_roles",listOf("EVENT_ADMIN","CASHIER"))
                    .claim("tiket_events",listOf(event)).claim("tiket_players",listOf(player))
                    .claim("tiket_pos",listOf("7")).claim("tiket_cashier","cashier").build())
                token.sign(RSASSASigner(SecuredTransportConfiguration.key.private))
                val metadata=Metadata()
                metadata.put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer "+token.serialize())
                return ClientInterceptors.intercept(channel,MetadataUtils.newAttachHeadersInterceptor(metadata))
            }
            val secured=channels.map { secure(it) }
            val workers=Executors.newFixedThreadPool(2)
            fun <T> concurrent(work: (Int)->T): List<T> =
                (0..1).map { n -> workers.submit(Callable { work(n) }) }.map { it.get(30,TimeUnit.SECONDS) }
            try {
                val dayRpc=RaceDayServiceGrpc.newBlockingStub(secured[0])
                val definition=RaceDayConfiguration.newBuilder().setRaceDayId(event).setEventDate("2026-11-11")
                    .setDisplayNumber(1).setCurrency("PLN").setTimeZone("Europe/Warsaw").setRulesVersion("v1")
                    .setMaximumCouponLegs(100).setMaximumOpenRaces(1).setNoWinnerPolicy(NoWinnerPolicy.HOUSE_RETAINS_POOL)
                val raceDefinition=RaceConfiguration.newBuilder().setRaceId(race).setNumber(1).setTitle("Race").setRacecourseName("Course")
                for(h in listOf(horse,id(),id()).withIndex()) raceDefinition.addRunners(
                    Runner.newBuilder().setHorseId(h.value).setProgramNumber((h.index+1).toString()).setDisplayName("Runner"))
                for(pool in listOf(PoolType.WIN,PoolType.QUINELLA,PoolType.EXACTA,PoolType.TRIFECTA))
                    raceDefinition.addPools(PoolConfiguration.newBuilder().setType(pool).setUnitStake(100).setTakeBasisPoints(1500))
                definition.addRaces(raceDefinition)
                dayRpc.createRaceDay(CreateRaceDayRequest.newBuilder().setDefinition(definition).setIdempotencyKey(id()).build())
                EnrollmentServiceGrpc.newBlockingStub(secured[0]).enrollPlayer(EnrollPlayerRequest.newBuilder()
                    .setRaceDayId(event).setPlayerId(player).setOpeningPaid(10000).setExpectedDayVersion(0).setIdempotencyKey(id()).build())
                fun day(version: Long)=DayCommand.newBuilder().setRaceDayId(event).setExpectedVersion(version).setIdempotencyKey(id()).build()
                dayRpc.publishRaceDay(day(0)); dayRpc.startRaceDay(day(1))
                fun rc(version:Long)=RaceCommand.newBuilder().setRaceDayId(event).setRaceId(race).setExpectedDayVersion(2)
                    .setExpectedRaceVersion(version).setIdempotencyKey(id()).build()
                RaceServiceGrpc.newBlockingStub(secured[0]).openBetting(rc(0))
                val selection=BettingSelection.newBuilder().setPositional(PositionalSelection.newBuilder()
                    .addPositions(Position.newBuilder().setHorseId(horse))).build()
                val quote=BettingServiceGrpc.newBlockingStub(secured[0]).quoteCoupon(QuoteCouponRequest.newBuilder()
                    .setRaceDayId(event).setRaceId(race).setBetType(PoolType.WIN).setSelection(selection).setUnitsPerCombination(1).build())
                val purchase=PurchaseCouponRequest.newBuilder().setRaceDayId(event).setRaceId(race).setPlayerId(player)
                    .setCouponId(id()).setBetType(PoolType.WIN).setSelection(selection).setUnitsPerCombination(1)
                    .setQuoteConfirmation(quote.confirmation).setExpectedDayVersion(quote.raceDayVersion).setExpectedRaceVersion(quote.raceVersion)
                    .setExpectedAccountVersion(0).setPointOfSaleNumber(7).setCashierIdentifier("cashier").setIdempotencyKey(id()).build()
                val jdbcBefore=first.getBean(JdbcTemplate::class.java)
                val commandsBefore=jdbcBefore.queryForObject("SELECT count(*) FROM command_idempotency",Int::class.java)
                for (forged in listOf(
                    purchase.toBuilder().setPointOfSaleNumber(99).build(),
                    purchase.toBuilder().setCashierIdentifier("forged-cashier").build(),
                    purchase.toBuilder().setPlayerId(id()).build(),
                    purchase.toBuilder().setRaceDayId(id()).build()
                )) {
                    assertEquals(Status.Code.PERMISSION_DENIED,assertThrows(StatusRuntimeException::class.java) {
                        BettingServiceGrpc.newBlockingStub(secured[1]).purchaseCoupon(forged)
                    }.status.code)
                }
                assertEquals(commandsBefore,jdbcBefore.queryForObject("SELECT count(*) FROM command_idempotency",Int::class.java))
                assertEquals(0,jdbcBefore.queryForObject("SELECT count(*) FROM account_movement",Int::class.java))
                assertEquals(0,jdbcBefore.queryForObject("SELECT count(*) FROM coupon",Int::class.java))
                assertEquals(0,jdbcBefore.queryForObject("SELECT count(*) FROM print_job",Int::class.java))
                val purchases=concurrent { BettingServiceGrpc.newBlockingStub(secured[it]).purchaseCoupon(purchase) }
                assertEquals(purchases[0],purchases[1])
                assertEquals(Status.Code.PERMISSION_DENIED,assertThrows(StatusRuntimeException::class.java) {
                    BettingServiceGrpc.newBlockingStub(secure(channels[1],"other-operator")).purchaseCoupon(purchase)
                }.status.code)
                assertEquals(Status.Code.ALREADY_EXISTS,assertThrows(StatusRuntimeException::class.java) {
                    BettingServiceGrpc.newBlockingStub(secured[1]).purchaseCoupon(purchase.toBuilder().setUnitsPerCombination(2).build())
                }.status.code)
                val jdbc=first.getBean(JdbcTemplate::class.java)
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM coupon WHERE race_day_id=?",Int::class.java,UUID.fromString(event)))
                val outbox1=first.getBean(PrintOutbox::class.java); val outbox2=second.getBean(PrintOutbox::class.java)
                val lease1=outbox1.claim()!!
                assertNull(outbox2.claim())
                jdbc.update("UPDATE print_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",lease1.job)
                val lease2=outbox2.claim()!!
                assertEquals(lease1.job,lease2.job); assertNotEquals(lease1.owner,lease2.owner)
                outbox1.complete(lease1,pl.umamusume.tiket.printing.PrintResult.Dispatched)
                assertEquals("CLAIMED",jdbc.queryForObject("SELECT status FROM print_job WHERE id=?",String::class.java,lease2.job))
                outbox2.complete(lease2,pl.umamusume.tiket.printing.PrintResult.Dispatched)
                RaceServiceGrpc.newBlockingStub(secured[1]).voidRace(rc(1))
                dayRpc.finalizeRaceDay(day(2)); dayRpc.completeRaceDay(day(3))
                val aq=AccountQuery.newBuilder().setRaceDayId(event).setPlayerId(player).build()
                val account=AccountServiceGrpc.newBlockingStub(secured[0]).getAccount(aq)
                val cash=CashOutRequest.newBuilder().setRaceDayId(event).setPlayerId(player).setExpectedDayVersion(4)
                    .setExpectedAccountVersion(account.accountVersion).setIdempotencyKey(id()).build()
                val receipts=concurrent { AccountServiceGrpc.newBlockingStub(secured[it]).cashOut(cash) }
                assertEquals(receipts[0],receipts[1])
                dayRpc.closeRaceDay(day(4))
                assertEquals(receipts[0],AccountServiceGrpc.newBlockingStub(secured[1]).cashOut(cash))
                val error=assertThrows(StatusRuntimeException::class.java) {
                    AccountServiceGrpc.newBlockingStub(secure(channels[1],"other-operator")).cashOut(cash)
                }
                assertEquals(Status.Code.PERMISSION_DENIED,error.status.code)
            } finally {
                workers.shutdownNow()
                channels.forEach { it.shutdownNow().awaitTermination(5,TimeUnit.SECONDS) }
            }
        } }
    }
}
