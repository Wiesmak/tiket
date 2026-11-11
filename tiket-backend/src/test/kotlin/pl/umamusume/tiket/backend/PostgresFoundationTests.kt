package pl.umamusume.tiket.backend

import com.zaxxer.hikari.HikariDataSource
import io.grpc.ManagedChannelBuilder
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.umamusume.tiket.contracts.v1.PingRequest
import pl.umamusume.tiket.contracts.v1.SystemServiceGrpc
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Testcontainers(disabledWithoutDocker = true)
class PostgresFoundationTests {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }

    @Test
    fun databaseMigrationAndGrpcSurviveRepeatedApplicationStartup() {
        repeat(2) {
            var port = 0
            lateinit var pool: HikariDataSource
            SpringApplicationBuilder(TiketBackendApplication::class.java).run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.grpc.server.address=127.0.0.1",
                "--spring.grpc.server.port=0"
            ).use { context ->
                pool = context.getBean(DataSource::class.java) as HikariDataSource
                pool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT 1").use { result ->
                            assertThat(result.next()).isTrue()
                            assertThat(result.getInt(1)).isEqualTo(1)
                        }
                    }
                }
                val flyway = context.getBean(Flyway::class.java)
                flyway.validate()
                assertThat(flyway.info().applied()).hasSize(5)
                assertThat(flyway.info().current().version.version).isEqualTo("5")
                assertThat(flyway.migrate().migrationsExecuted).isZero()
                assertThat(context.environment.getProperty("local.server.port")).isNull()
                assertThat(context.javaClass.name).doesNotContain("WebServer")
                port = context.environment.getRequiredProperty("local.grpc.server.port", Int::class.java)
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
            assertThat(pool.isClosed).isTrue()
            assertThat(runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            }.isFailure).isTrue()
        }
    }
}
