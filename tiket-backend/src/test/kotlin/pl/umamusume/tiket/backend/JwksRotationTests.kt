package pl.umamusume.tiket.backend

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.*
import com.nimbusds.jwt.*
import com.sun.net.httpserver.HttpServer
import io.grpc.*
import io.grpc.stub.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.boot.test.context.*
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort
import org.springframework.context.annotation.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.*
import org.springframework.test.context.ActiveProfiles
import pl.umamusume.tiket.backend.security.*
import pl.umamusume.tiket.contracts.v1.*
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.*
import java.util.concurrent.TimeUnit

@TestConfiguration
@Import(SecurityConfiguration::class,ResourceAuthorization::class)
class RotationConfiguration {
    companion object {
        val first=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val second=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val keys=java.util.concurrent.atomic.AtomicReference(listOf(RSAKey.Builder(first.public as RSAPublicKey).keyID("first").build()))
        val jwks=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            createContext("/keys") { exchange ->
                val bytes=JWKSet(keys.get()).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type","application/json")
                exchange.sendResponseHeaders(200,bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }
    @Bean fun jdbc()=JdbcTemplate(org.springframework.jdbc.datasource.DriverManagerDataSource())
    @Bean @Primary fun decoder(): JwtDecoder = NimbusJwtDecoder.withJwkSetUri("http://127.0.0.1:"+jwks.address.port+"/keys").build().also {
        it.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://issuer.test"))
    }
    @Bean fun account()=object: AccountServiceGrpc.AccountServiceImplBase() {
        override fun getAccount(r: AccountQuery,o: StreamObserver<AccountInformation>) {
            o.onNext(AccountInformation.newBuilder().setPlayerId(r.playerId).build());o.onCompleted()
        }
    }
}

@ActiveProfiles("security-test")
@SpringBootTest(classes=[TransportTestConfiguration::class,RotationConfiguration::class],properties=[
    "spring.grpc.server.port=0","spring.main.web-application-type=servlet",
    "tiket.security.issuer=https://issuer.test","tiket.security.jwks=https://issuer.test/keys","tiket.security.audience=tiket",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
])
class JwksRotationTests {
    @LocalGrpcServerPort var port:Int=0
    companion object { @JvmStatic @AfterAll fun stopJwks() { RotationConfiguration.jwks.stop(0) } }
    @Test fun normalRotationAndUnknownKeyThroughNetty() {
        val event=UUID.randomUUID().toString();val player=UUID.randomUUID().toString()
        fun token(key:java.security.KeyPair,kid:String):String {
            val jwt=SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),JWTClaimsSet.Builder()
                .issuer("https://issuer.test").audience("tiket").subject("player").expirationTime(Date(System.currentTimeMillis()+120000))
                .claim("tiket_roles",listOf("PLAYER")).claim("tiket_events",listOf(event)).claim("tiket_player",player).build())
            jwt.sign(RSASSASigner(key.private));return jwt.serialize()
        }
        val channel=ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build()
        try {
            fun call(token:String):AccountInformation {
                val metadata=Metadata();metadata.put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer "+token)
                return AccountServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .getAccount(AccountQuery.newBuilder().setRaceDayId(event).setPlayerId(player).build())
            }
            assertEquals(player,call(token(RotationConfiguration.first,"first")).playerId)
            val rotated=token(RotationConfiguration.second,"second")
            val denied=assertThrows(StatusRuntimeException::class.java) { call(rotated) }
            assertTrue(denied.status.code in setOf(Status.Code.UNAUTHENTICATED,Status.Code.PERMISSION_DENIED))
            RotationConfiguration.keys.set(RotationConfiguration.keys.get()+RSAKey.Builder(RotationConfiguration.second.public as RSAPublicKey).keyID("second").build())
            assertEquals(player,call(rotated).playerId)
            assertEquals(player,call(token(RotationConfiguration.first,"first")).playerId)
        } finally { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS) }
    }
}
