package pl.umamusume.tiket.backend.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.grpc.server.GlobalServerInterceptor
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor
import org.springframework.grpc.server.security.GrpcSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.*
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.security.web.SecurityFilterChain
import java.net.URI

@Configuration
@org.springframework.context.annotation.Profile("!test")
class SecurityConfiguration(private val env: Environment) {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(JwtDecoder::class)
    fun jwtDecoder(): JwtDecoder {
        val issuer = env.getRequiredProperty("tiket.security.issuer")
        val jwks = env.getRequiredProperty("tiket.security.jwks")
        val audience = env.getRequiredProperty("tiket.security.audience")
        require(issuer.isNotBlank() && audience.isNotBlank())
        require(URI(issuer).scheme == "https" && URI(jwks).scheme == "https") {
            "JWT issuer and JWKS must use HTTPS"
        }
        val decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build()
        val claims = OAuth2TokenValidator<Jwt> { token ->
            if (token.expiresAt != null && !token.subject.isNullOrBlank() && audience in token.audience.orEmpty()
                    .orEmpty()
            )
                OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
        }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(issuer), claims))
        return decoder
    }

    @Bean
    @GlobalServerInterceptor
    fun grpcAuthentication(grpc: GrpcSecurity, decoder: JwtDecoder): AuthenticationProcessInterceptor =
        grpc.authorizeRequests { rules ->
            rules.methods("pl.umamusume.tiket.api.v1.SystemService/Ping").permitAll()
                .allRequests().authenticated()
        }.oauth2ResourceServer { it.jwt { jwt -> jwt.decoder(decoder) } }.build()

    @Bean
    fun managementSecurity(http: HttpSecurity): SecurityFilterChain =
        http.authorizeHttpRequests {
            it.requestMatchers("/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus")
                .permitAll()
                .anyRequest().denyAll()
        }.build()
}
