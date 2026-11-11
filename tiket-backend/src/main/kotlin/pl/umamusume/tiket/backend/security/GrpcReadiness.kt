package pl.umamusume.tiket.backend.security

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle
import org.springframework.stereotype.Component

@Component("grpcHealthIndicator")
@Profile("!test")
class GrpcReadiness(private val lifecycle: GrpcServerLifecycle) : HealthIndicator {
    override fun health(): Health = if (lifecycle.isRunning) Health.up().build() else Health.down().build()
}
