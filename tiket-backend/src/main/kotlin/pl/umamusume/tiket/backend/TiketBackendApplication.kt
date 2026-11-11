package pl.umamusume.tiket.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TiketBackendApplication

fun main(args: Array<String>) {
    if ("--tiket.migrate-only" in args) {
        require(args.size == 1) { "Migration-only mode accepts no application arguments" }
        migrateOnly()
        return
    }
    runApplication<TiketBackendApplication>(*args)
}
