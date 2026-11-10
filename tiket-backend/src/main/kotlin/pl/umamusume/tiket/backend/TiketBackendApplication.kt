package pl.umamusume.tiket.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TiketBackendApplication

fun main(args: Array<String>) {
    runApplication<TiketBackendApplication>(*args)
}
