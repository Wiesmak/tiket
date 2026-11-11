package pl.umamusume.tiket.backend.security

import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
@Profile("production")
class ProductionGuard(env: Environment) {
    init {
        require("test" !in env.activeProfiles && "dev" !in env.activeProfiles)
        when (env.getRequiredProperty("tiket.transport")) {
            "direct-tls" -> {
                require(env.getProperty("spring.grpc.server.ssl.enabled", Boolean::class.java, false))
                require(!env.getProperty("spring.grpc.server.ssl.bundle").isNullOrBlank())
                require(env.getProperty("spring.grpc.server.ssl.secure", Boolean::class.java, true))
            }

            "gateway-h2c" -> {
                require(!env.getProperty("spring.grpc.server.ssl.enabled", Boolean::class.java, false))
            }

            else -> error("Explicit direct-tls or gateway-h2c transport required")
        }
        require(env.getProperty("server.port", Int::class.java, -1) == -1) { "Business HTTP listener must be disabled" }
        require(env.getProperty("spring.main.web-application-type") == "servlet")
    }
}
