package pl.umamusume.tiket.backend

import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import pl.umamusume.tiket.backend.grpc.SystemGrpcService

@Configuration
@EnableAutoConfiguration
@Import(SystemGrpcService::class)
class TransportTestConfiguration
