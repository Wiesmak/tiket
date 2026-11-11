package pl.umamusume.tiket.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

@Testcontainers
class MigrationOnlyTests {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer("postgres:18.1")
    }

    @Test
    fun freshAndPopulatedV5ValidateWithSeparateRuntimeRole() {
        val password = UUID.randomUUID().toString()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE ROLE migrator LOGIN PASSWORD '$password'")
                s.execute("CREATE ROLE runtime LOGIN PASSWORD '$password'")
                s.execute("ALTER SCHEMA public OWNER TO migrator")
            }
        }
        val env = mapOf("DB_URL" to postgres.jdbcUrl, "DB_USERNAME" to "migrator", "DB_PASSWORD" to password)
        migrateOnly(env)
        val day = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, "migrator", password).use { c ->
            c.createStatement().use { s ->
                s.execute("INSERT INTO race_day VALUES ('$day','2026-11-11',1,'PLN','Europe/Warsaw','v1',10000,1,'HOUSE_RETAINS_POOL')")
                s.execute("GRANT USAGE ON SCHEMA public TO runtime")
                s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO runtime")
                s.execute("REVOKE UPDATE,DELETE,TRUNCATE ON operational_audit,command_actor FROM runtime")
            }
        }
        migrateOnly(env) // populated V5: validation and no-op upgrade, no invented V6.
        DriverManager.getConnection(postgres.jdbcUrl, "runtime", password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM race_day").use { r -> r.next(); assertThat(r.getInt(1)).isEqualTo(1) }
                assertThrows<SQLException> { s.execute("CREATE TABLE forbidden(id int)") }
                assertThrows<SQLException> { s.execute("UPDATE operational_audit SET outcome='changed'") }
            }
        }
    }

    @Test
    fun missingMigrationCredentialsFailBeforeCreatingAnyApplicationContext() {
        assertThrows<IllegalArgumentException> { migrateOnly(emptyMap()) }
    }
}
