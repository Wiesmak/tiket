package pl.umamusume.tiket.backend

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.*
import io.grpc.*
import io.grpc.stub.MetadataUtils
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.*
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.*
import org.testcontainers.junit.jupiter.*
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.umamusume.tiket.contracts.v1.*
import java.util.*
import java.util.concurrent.TimeUnit

@TestConfiguration
class FinancialJwtConfiguration {
    @Bean @Primary fun testDecoder(): JwtDecoder = SecuredTransportConfiguration().decoder()
}

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
@Testcontainers
@ActiveProfiles("security-test")
@Import(FinancialJwtConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT, properties=[
    "spring.grpc.server.port=0", "management.server.port=0", "spring.main.web-application-type=servlet",
    "management.endpoint.health.group.readiness.include=readinessState,db",
    "tiket.security.issuer=https://issuer.test", "tiket.security.jwks=https://issuer.test/keys",
    "tiket.security.audience=tiket", "tiket.printing.enabled=false"
])
class FinancialSecurityTests {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic @DynamicPropertySource fun database(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl + "?socketTimeout=2" }
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
            r.add("spring.datasource.hikari.connection-timeout") { "1000" }
        }
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var env: Environment
    @LocalGrpcServerPort var port: Int = 0

    @Test @Order(1) fun deniedFinancialCommandsLeaveNoFinancialEvidence(output: org.springframework.boot.test.system.CapturedOutput) {
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build()
        try {
            val day = UUID.randomUUID().toString()
            val jwt = SignedJWT(JWSHeader(JWSAlgorithm.RS256), JWTClaimsSet.Builder().issuer("https://issuer.test")
                .audience("tiket").subject("player").expirationTime(Date(System.currentTimeMillis()+120000))
                .claim("tiket_roles",listOf("PLAYER")).claim("tiket_events",listOf(day)).build())
            jwt.sign(RSASSASigner(SecuredTransportConfiguration.key.private))
            val metadata = Metadata()
            metadata.put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer "+jwt.serialize())
            val secured = ClientInterceptors.intercept(channel,MetadataUtils.newAttachHeadersInterceptor(metadata))
            fun denied(work: () -> Unit) {
                assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException::class.java, work).status.code)
            }
            denied { BettingServiceGrpc.newBlockingStub(secured).purchaseCoupon(PurchaseCouponRequest.newBuilder().setRaceDayId(day).build()) }
            denied { AccountServiceGrpc.newBlockingStub(secured).cashOut(CashOutRequest.newBuilder().setRaceDayId(day).build()) }
            denied { ResultServiceGrpc.newBlockingStub(secured).settleRace(SettleRaceRequest.getDefaultInstance()) }
            denied { RaceServiceGrpc.newBlockingStub(secured).voidRace(RaceCommand.newBuilder().setRaceDayId(day).build()) }
            for (table in listOf("account_movement","coupon","pool_selection_units","settlement","leg_resolution","print_job","command_idempotency"))
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Int::class.java))
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM operational_audit WHERE outcome='DENIED'",Int::class.java)!! >= 4)
            assertTrue(output.all.contains("\"grpc_method\""))
            assertTrue(output.all.contains("\"request_id\""))
            assertTrue(output.all.contains("\"service\""))
            assertTrue(output.all.contains("\"environment\""))
            assertFalse(output.all.contains(jwt.serialize()))
            assertFalse(output.all.contains("Bearer " + jwt.serialize()))
        } finally { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS) }
    }
    @Test @Order(2) fun databaseOutageAffectsReadinessOnly() {
        val managementPort = env.getRequiredProperty("local.management.port",Int::class.java)
        val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
        fun health(group: String) = client.send(java.net.http.HttpRequest.newBuilder(
            java.net.URI("http://127.0.0.1:" + managementPort + "/actuator/health/" + group))
            .timeout(java.time.Duration.ofSeconds(15)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        assertEquals(200,health("readiness").statusCode())
        postgres.stop()
        assertEquals(503,health("readiness").statusCode())
        assertEquals(200,health("liveness").statusCode())
    }
}
