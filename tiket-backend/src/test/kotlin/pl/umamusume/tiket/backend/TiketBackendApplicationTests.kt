package pl.umamusume.tiket.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.grpc.client.ImportGrpcClients
import pl.umamusume.tiket.contracts.v1.PingRequest
import pl.umamusume.tiket.contracts.v1.SystemServiceGrpc
import pl.umamusume.tiket.core.money.Money
import java.util.concurrent.TimeUnit

// Transport tests are independent of database availability; PostgreSQL is tested separately.
@SpringBootTest(classes = [TransportTestConfiguration::class], properties = [
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
])
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = [SystemServiceGrpc.SystemServiceBlockingStub::class])
class TiketBackendApplicationTests {
    @Autowired
    lateinit var stub: SystemServiceGrpc.SystemServiceBlockingStub

    @Test
    fun pingThroughOfficialTestTransport() {
        val reply = stub.withDeadlineAfter(5, TimeUnit.SECONDS).ping(PingRequest.getDefaultInstance())
        assertThat(reply.status).isEqualTo("OK")
        assertThat(reply.apiVersion).isEqualTo("v1")
    }

    @Test
    fun backendCanConsumePublicCoreApi() {
        assertThat((Money(100) + Money(25)).amount).isEqualTo(125)
    }
}
