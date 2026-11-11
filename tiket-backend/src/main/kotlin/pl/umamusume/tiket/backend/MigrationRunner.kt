package pl.umamusume.tiket.backend

import org.flywaydb.core.Flyway

/** Deliberately creates no Spring context, listeners, schedulers or print workers. */
internal fun migrateOnly(environment: Map<String, String> = System.getenv()) {
    fun required(name: String) = requireNotNull(environment[name]?.takeIf { it.isNotBlank() }) { "$name required" }
    Flyway.configure()
        .dataSource(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"))
        .locations("classpath:db/migration")
        .validateOnMigrate(true)
        .load()
        .migrate()
}
