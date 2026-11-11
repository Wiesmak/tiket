package pl.umamusume.tiket.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.*
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.JwtDecoder
import pl.umamusume.tiket.backend.security.*

@org.springframework.boot.test.context.TestConfiguration
@Import(ProductionGuard::class,ProfileGuard::class)
class ProductionGuardConfiguration {
    @Bean fun decoder(env:Environment):JwtDecoder=SecurityConfiguration(env).jwtDecoder()
}

class ProductionConfigurationTests {
    private fun runner(vararg properties:String)=ApplicationContextRunner()
        .withInitializer { it.environment.setActiveProfiles("production") }
        .withUserConfiguration(ProductionGuardConfiguration::class.java)
        .withPropertyValues("spring.main.web-application-type=servlet","server.port=-1",*properties)
    private val jwt=arrayOf("tiket.security.issuer=https://issuer.test","tiket.security.jwks=https://issuer.test/keys","tiket.security.audience=tiket")
    @Test fun missingJwtFailsClosed() {
        runner("tiket.transport=gateway-h2c").run { assertThat(it).hasFailed() }
    }
    @Test fun explicitTransportRequired() {
        runner(*jwt).run { assertThat(it).hasFailed() }
    }
    @Test fun directTlsRequiresBundleAndEnablement() {
        runner(*jwt,"tiket.transport=direct-tls","spring.grpc.server.ssl.enabled=true").run { assertThat(it).hasFailed() }
    }
    @Test fun gatewayCannotAccidentallyEnableTls() {
        runner(*jwt,"tiket.transport=gateway-h2c","spring.grpc.server.ssl.enabled=true").run { assertThat(it).hasFailed() }
    }
    @Test fun publicBusinessHttpPortRejected() {
        runner(*jwt,"tiket.transport=gateway-h2c","server.port=8080").run { assertThat(it).hasFailed() }
    }
    @Test fun validGatewayConfigurationDoesNotContactIssuerAtStartup() {
        runner(*jwt,"tiket.transport=gateway-h2c").run { assertThat(it).hasNotFailed() }
    }
    @Test fun productionRejectsTestProfile() {
        runner(*jwt,"tiket.transport=gateway-h2c")
            .withInitializer { it.environment.setActiveProfiles("production","test") }
            .run { assertThat(it).hasFailed() }
    }
}
