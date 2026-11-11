package pl.umamusume.tiket.backend

import io.grpc.Context
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.grpc.server.security.GrpcSecurity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.*
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.umamusume.tiket.backend.persistence.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable

@Testcontainers
@SpringBootTest(properties=["spring.grpc.server.port=0", "tiket.printing.enabled=false"])
class AuditPersistenceTests {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic @DynamicPropertySource fun database(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
        }
    }
    @Autowired lateinit var commands: IdempotencyRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    private fun <T> actor(subject: String, work: () -> T): T {
        val jwt = Jwt.withTokenValue("test-only").header("alg","RS256").subject(subject)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build()
        return Context.current().withValue(GrpcSecurity.SECURITY_CONTEXT_KEY,
            SecurityContextImpl(JwtAuthenticationToken(jwt))).call(Callable { work() })
    }
    private fun count(key: CommandKey) = jdbc.queryForObject(
        "SELECT count(*) FROM operational_audit WHERE resource_scope=?", Int::class.java, key.scope)!!
    @Test fun auditAndActorCommitReplayAndRollbackTogether() {
        val key = CommandKey("test", UUID.randomUUID().toString(), "key")
        actor("cashier-a") {
            assertThrows(IllegalStateException::class.java) {
                commands.execute(key, "digest") { error("forced rollback") }
            }
            assertEquals(0, count(key))
            assertEquals("exact-response", commands.execute(key, "digest") { "exact-response" })
            assertEquals("exact-response", commands.execute(key, "digest") { error("must not execute again") })
            assertEquals(2, count(key))
            assertThrows(IdempotencyConflictException::class.java) { commands.execute(key, "changed") { "changed" } }
        }
        actor("cashier-b") {
            assertThrows(io.grpc.StatusRuntimeException::class.java) { commands.execute(key, "digest") { "stolen" } }
        }
        assertEquals(2, count(key))
        assertThrows(org.springframework.dao.DataAccessException::class.java) {
            jdbc.update("DELETE FROM operational_audit WHERE resource_scope=?", key.scope)
        }
        assertEquals(2, count(key))
    }
}
