package pl.umamusume.tiket.backend

import io.grpc.ManagedChannelBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import pl.umamusume.tiket.contracts.v1.PingRequest
import pl.umamusume.tiket.contracts.v1.SystemServiceGrpc
import java.util.concurrent.TimeUnit

@SpringBootTest(classes = [TransportTestConfiguration::class], properties = [
    "spring.grpc.server.port=0",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
])
class NettyGrpcTests {
    @LocalGrpcServerPort
    var port: Int = 0

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun pingThroughLoopbackNetty() {
        assertThat(context.javaClass.name).doesNotContain("WebServer")
        assertThat(context.environment.getProperty("local.server.port")).isNull()
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
        try {
            val reply = SystemServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS).ping(PingRequest.getDefaultInstance())
            assertThat(reply.status).isEqualTo("OK")
            assertThat(reply.apiVersion).isEqualTo("v1")
        } finally {
            channel.shutdownNow()
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }
}
