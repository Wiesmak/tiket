package pl.umamusume.tiket.backend

import io.grpc.*
import io.grpc.netty.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort
import org.springframework.test.context.*
import pl.umamusume.tiket.contracts.v1.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("security-test")
@SpringBootTest(classes=[TransportTestConfiguration::class,SecuredTransportConfiguration::class],properties=[
    "spring.grpc.server.port=0","spring.main.web-application-type=servlet",
    "spring.grpc.server.ssl.bundle=transport-test","spring.grpc.server.ssl.client-auth=require",
    "tiket.security.issuer=https://issuer.test","tiket.security.jwks=https://issuer.test/keys","tiket.security.audience=tiket",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
])
class MtlsTransportTests {
    @LocalGrpcServerPort var port:Int=0
    companion object {
        @JvmStatic @DynamicPropertySource fun tls(registry:DynamicPropertyRegistry) {
            SecurityTlsTransportTests.tls(registry)
            registry.add("spring.ssl.bundle.jks.transport-test.truststore.location") { SecurityTlsTransportTests.keystore.toUri().toString() }
            registry.add("spring.ssl.bundle.jks.transport-test.truststore.password") { SecurityTlsTransportTests.password }
            registry.add("spring.ssl.bundle.jks.transport-test.truststore.type") { "PKCS12" }
        }
    }
    private fun channel(certificate:Boolean):ManagedChannel {
        val ssl=GrpcSslContexts.forClient().trustManager(SecurityTlsTransportTests.certificate())
        if(certificate) {
            val store=java.security.KeyStore.getInstance("PKCS12")
            java.nio.file.Files.newInputStream(SecurityTlsTransportTests.keystore).use {
                store.load(it,SecurityTlsTransportTests.password.toCharArray())
            }
            ssl.keyManager(store.getKey("server",SecurityTlsTransportTests.password.toCharArray()) as java.security.PrivateKey,
                SecurityTlsTransportTests.certificate())
        }
        return NettyChannelBuilder.forAddress("localhost",port).sslContext(ssl.build()).build()
    }
    @Test fun noClientCertificateFailsTransport() {
        val c=channel(false)
        try {
            assertEquals(Status.Code.UNAVAILABLE,assertThrows(StatusRuntimeException::class.java) {
                SystemServiceGrpc.newBlockingStub(c).withDeadlineAfter(5,TimeUnit.SECONDS).ping(PingRequest.getDefaultInstance())
            }.status.code)
        } finally { c.shutdownNow().awaitTermination(5,TimeUnit.SECONDS) }
    }
    @Test fun clientCertificateDoesNotReplaceJwt() {
        val c=channel(true)
        try {
            assertEquals("OK",SystemServiceGrpc.newBlockingStub(c).withDeadlineAfter(5,TimeUnit.SECONDS).ping(PingRequest.getDefaultInstance()).status)
            val error=assertThrows(StatusRuntimeException::class.java) {
                AccountServiceGrpc.newBlockingStub(c).withDeadlineAfter(5,TimeUnit.SECONDS).getAccount(AccountQuery.getDefaultInstance())
            }
            assertTrue(error.status.code in setOf(Status.Code.UNAUTHENTICATED,Status.Code.PERMISSION_DENIED))
        } finally { c.shutdownNow().awaitTermination(5,TimeUnit.SECONDS) }
    }
}
