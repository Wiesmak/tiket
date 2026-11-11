package pl.umamusume.tiket.backend

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.*
import io.grpc.*
import io.grpc.MethodDescriptor
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort
import org.springframework.context.annotation.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.*
import org.springframework.security.oauth2.core.*
import org.springframework.test.context.ActiveProfiles
import pl.umamusume.tiket.backend.security.*
import pl.umamusume.tiket.contracts.v1.*
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("security-test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT, classes = [TransportTestConfiguration::class, SecuredTransportConfiguration::class], properties = [
    "tiket.security.issuer=https://issuer.test", "tiket.security.jwks=https://issuer.test/keys", "tiket.security.audience=tiket",
    "spring.grpc.server.ssl.bundle=transport-test", "spring.grpc.server.port=0", "spring.main.web-application-type=servlet", "server.port=-1", "management.server.port=0",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
])
class SecurityTlsTransportTests {
    @Autowired lateinit var environment: org.springframework.core.env.Environment
    @LocalGrpcServerPort var port: Int = 0
    private lateinit var channel: ManagedChannel
    private val event = UUID.randomUUID().toString()
    private val player = UUID.randomUUID().toString()
    @BeforeEach fun connect() { channel = io.grpc.netty.NettyChannelBuilder.forAddress("localhost", port)
        .sslContext(io.grpc.netty.GrpcSslContexts.forClient().trustManager(certificate()).build()).build() }
    @AfterEach fun disconnect() { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }

    private fun token(role: String = "PLAYER", scope: String = event, owner: String = player,
                      issuer: String = "https://issuer.test", audience: String = "tiket",
                      expiry: Instant = Instant.now().plusSeconds(120), future: Boolean = false,
                      wrongSignature: Boolean = false): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).subject("operator").audience(audience)
            .expirationTime(Date.from(expiry)).notBeforeTime(Date.from(if (future) Instant.now().plusSeconds(120) else Instant.now().minusSeconds(1)))
            .claim("tiket_roles", listOf(role)).claim("tiket_events", listOf(scope)).claim("tiket_player", owner).build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
        val key = if (wrongSignature) KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() else SecuredTransportConfiguration.key
        jwt.sign(RSASSASigner(key.private))
        return jwt.serialize()
    }
    private fun read(bearer: String?): AccountInformation {
        var stub = AccountServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
        if (bearer != null) {
            val metadata = Metadata()
            metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + bearer)
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
        }
        return stub.getAccount(AccountQuery.newBuilder().setRaceDayId(event).setPlayerId(player).build())
    }
    private fun rejected(bearer: String?) {
        val error = assertThrows(StatusRuntimeException::class.java) { read(bearer) }
        assertTrue(error.status.code in setOf(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED))
    }
    @Test fun validOwner() { assertEquals(player, read(token()).playerId) }
    @Test fun auditor() { assertEquals(player, read(token(role="AUDITOR")).playerId) }
    @Test fun missingToken() = rejected(null)
    @Test fun malformedToken() = rejected("malformed")
    @Test fun badSignature() = rejected(token(wrongSignature=true))
    @Test fun wrongIssuer() = rejected(token(issuer="https://attacker.test"))
    @Test fun wrongAudience() = rejected(token(audience="another-app"))
    @Test fun expiredToken() = rejected(token(expiry=Instant.now().minusSeconds(120)))
    @Test fun futureToken() = rejected(token(future=true))
    @Test fun crossAccount() = rejected(token(owner=UUID.randomUUID().toString()))
    @Test fun wrongEvent() = rejected(token(scope=UUID.randomUUID().toString()))
    @Test fun adminCannotReadFinancialAccount() = rejected(token(role="EVENT_ADMIN"))
    @Test fun forgedIdentityMetadataDoesNotAuthenticate() {
        val metadata = Metadata()
        metadata.put(Metadata.Key.of("x-role", Metadata.ASCII_STRING_MARSHALLER), "AUDITOR")
        val stub = AccountServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
        assertThrows(StatusRuntimeException::class.java) {
            stub.getAccount(AccountQuery.newBuilder().setRaceDayId(event).setPlayerId(player).build())
        }
    }
    companion object {
        internal val password = "isolated-test-password"
        internal val keystore = java.nio.file.Files.createTempDirectory("tiket-tls-").resolve("server.p12").also { path ->
            path.toFile().deleteOnExit()
            val executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
            val process = ProcessBuilder(executable.toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", password).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            check(process.waitFor() == 0)
        }
        internal fun certificate(): java.security.cert.X509Certificate {
            val store = java.security.KeyStore.getInstance("PKCS12")
            java.nio.file.Files.newInputStream(keystore).use { store.load(it, password.toCharArray()) }
            return store.getCertificate("server") as java.security.cert.X509Certificate
        }
        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun tls(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            registry.add("spring.ssl.bundle.jks.transport-test.keystore.location") { keystore.toUri().toString() }
            registry.add("spring.ssl.bundle.jks.transport-test.keystore.password") { password }
            registry.add("spring.ssl.bundle.jks.transport-test.keystore.type") { "PKCS12" }
        }
        @JvmStatic fun protectedMethods() = SecuredTransportConfiguration.services.flatMap { it.methods }.stream()
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("protectedMethods")
    fun everyBusinessMethodRejectsAnonymous(raw: MethodDescriptor<*, *>) {
        @Suppress("UNCHECKED_CAST")
        val method = raw as MethodDescriptor<Any, Any>
        val request = method.requestMarshaller.parse(java.io.ByteArrayInputStream(byteArrayOf()))
        val error = assertThrows(StatusRuntimeException::class.java) {
            io.grpc.stub.ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), request)
        }
        assertTrue(error.status.code in setOf(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED))
        assertTrue(method.fullMethodName in RpcPolicy.methods)
    }

    @Test fun separateManagementListener() {
        val managementPort = environment.getRequiredProperty("local.management.port", Int::class.java)
        assertNotEquals(port, managementPort)
        val client = java.net.http.HttpClient.newHttpClient()
        fun get(path: String) = client.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:" + managementPort + path)).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString())
        assertEquals(200, get("/actuator/health/liveness").statusCode())
        assertEquals(200, get("/actuator/health/readiness").statusCode())
        val metrics = get("/actuator/prometheus")
        assertEquals(200, metrics.statusCode())
        assertTrue(metrics.body().contains("jvm_memory"))
        assertFalse(metrics.body().contains(player))
        assertFalse(metrics.body().contains(event))
        assertTrue(get("/actuator/env").statusCode() in setOf(403, 404))
        assertTrue(get("/business").statusCode() in setOf(403, 404))
    }

}
