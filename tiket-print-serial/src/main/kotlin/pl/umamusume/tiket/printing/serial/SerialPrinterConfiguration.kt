package pl.umamusume.tiket.printing.serial

import java.util.Locale

data class SerialPrinterConfiguration(
    val port: String,
    val baud: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Int = 0,
    val writeTimeoutMillis: Int = 5000,
    val density: Int? = 50,
    val speed: Int? = 5,
    val feedLines: Int = 3,
    val bandHeight: Int = 128,
    val maxHeight: Int = 4000
) {
    init {
        require(port.isNotBlank() && port == port.trim() && port.none { it.isISOControl() }) { "Invalid serial port name" }
        require(baud in 1..4_000_000 && dataBits in 5..8 && stopBits in 1..3 && parity in 0..4) { "Invalid serial parameters" }
        require(writeTimeoutMillis in 1..60000 && (density == null || density in 0..127) && (speed == null || speed in 1..9))
        require(feedLines in 0..20 && bandHeight in 1..1024 && maxHeight in 256..20000)
    }
    val portKey: String get() = if (port.matches(Regex("(?i)COM[1-9][0-9]*"))) port.uppercase(Locale.ROOT) else port
}

class SerialRoutes(routes: Map<Int, SerialPrinterConfiguration>) {
    val routes: Map<Int, SerialPrinterConfiguration> = java.util.Collections.unmodifiableMap(LinkedHashMap(routes))
    val lanes: List<Set<Int>>
    init {
        require(routes.isNotEmpty() && routes.size <= 64 && routes.keys.all { it > 0 }) { "Serial routes require positive POS numbers (maximum 64)" }
        val groups = this.routes.entries.groupBy { it.value.portKey }
        require(groups.size <= 16) { "At most 16 physical ports supported" }
        require(groups.values.all { group -> group.map { it.value.copy(port = it.value.portKey) }.distinct().size == 1 }) { "Conflicting profiles for shared port" }
        lanes = groups.values.map { group -> group.map { it.key }.toSet() }
    }
}
