package pl.umamusume.tiket.backend.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import pl.umamusume.tiket.printing.TicketPrinter
import pl.umamusume.tiket.printing.serial.*

@ConfigurationProperties("tiket.printing.serial")
class SerialPrintingProperties {
    var routes: List<Route> = emptyList()
    class Route {
        var pos: Int = 0
        var port: String = ""
        var baud: Int = 9600
        var dataBits: Int = 8
        var stopBits: Int = 1
        var parity: Int = 0
        var writeTimeoutMillis: Int = 5000
        var vendorCommands: Boolean = true
        var density: Int = 50
        var speed: Int = 5
        var feedLines: Int = 3
        var bandHeight: Int = 128
        var maxHeight: Int = 4000
        fun profile() = SerialPrinterConfiguration(port, baud, dataBits, stopBits, parity,
            writeTimeoutMillis, if (vendorCommands) density else null, if (vendorCommands) speed else null,
            feedLines, bandHeight, maxHeight)
    }
    fun validated(): SerialRoutes {
        require(routes.map { it.pos }.distinct().size == routes.size) { "Duplicate serial POS route" }
        return SerialRoutes(routes.associate { it.pos to it.profile() })
    }
}

@Component
class PrintingBackendValidation(@Value("\${tiket.printing.backend:console}") backend: String) {
    init { require(backend in setOf("console", "serial")) { "Unsupported printing backend" } }
}

@Configuration
@ConditionalOnProperty(prefix = "tiket.printing", name = ["backend"], havingValue = "serial")
@EnableConfigurationProperties(SerialPrintingProperties::class)
class SerialPrintingConfiguration {
    @Bean
    fun serialRoutes(properties: SerialPrintingProperties,
        @Value("\${tiket.printing.lease-seconds:60}") leaseSeconds: Long): SerialRoutes {
        require(leaseSeconds >= 300) { "Serial printing requires tiket.printing.lease-seconds >= 300; size receipts for this lease" }
        return properties.validated()
    }

    @Bean
    @ConditionalOnMissingBean(TicketPrinter::class)
    fun serialTicketPrinter(routes: SerialRoutes): TicketPrinter = SerialTicketPrinter(routes)
}
