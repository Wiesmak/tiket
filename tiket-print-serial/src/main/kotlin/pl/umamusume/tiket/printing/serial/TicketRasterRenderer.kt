package pl.umamusume.tiket.printing.serial

import pl.umamusume.tiket.printing.TicketPrintData
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.time.format.DateTimeFormatter
import java.util.Locale

class TicketRasterRenderer {
    data class Line(val text: String, val size: Int, val bold: Boolean)
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun content(d: TicketPrintData): List<Line> = buildList {
        fun line(text: String, size: Int = 18, bold: Boolean = false) { add(Line(text, size, bold)) }
        line("TIKET", 30, true)
        line(if (d.isReprint) "REPRINT" else "ORIGINAL", 24, true)
        line(d.racecourseName, 22, true)
        line("Day ${d.raceDayNumber} / Race ${d.raceNumber}", 22, true)
        line("POS ${d.pointOfSaleNumber} / Cashier ${d.cashierIdentifier}")
        line("Bet: ${date.format(d.betDateTime)}")
        line("Print: ${date.format(d.printDateTime)}")
        line("--------------------------------")
        line(d.betType, 24, true)
        line("Selection: ${d.betString}", 22, true)
        line("Units/combination: ${d.unitsPerCombination}")
        line("Unit price: ${d.unitBetPrice} ${d.currency}")
        line("Combinations: ${d.combinationCount}")
        line("Total units: ${d.totalBetUnits}")
        line("--------------------------------")
        line("TOTAL: ${d.totalBetValue} ${d.currency}", 26, true)
        line("Ticket: ${d.ticketId}", 14)
        line("Attempt: ${d.printAttemptId}", 14)
    }

    fun render(d: TicketPrintData, maxHeight: Int = 4000): BufferedImage {
        require(maxHeight in 256..20000) { "Invalid receipt height limit" }
        require(listOf(d.racecourseName, d.cashierIdentifier, d.betString, d.currency)
            .sumOf { it.length.toLong() } <= 30000) { "Receipt content exceeds safe limit" }
        val source = content(d)
        require(source.sumOf { it.text.length.toLong() } <= 32000) { "Receipt content exceeds safe limit" }
        val scratch = BufferedImage(384, 1, BufferedImage.TYPE_BYTE_BINARY)
        val measure = scratch.createGraphics()
        val rows = mutableListOf<Line>()
        var height = 20
        try {
            for (line in source) {
                measure.font = Font(Font.MONOSPACED, if (line.bold) Font.BOLD else Font.PLAIN, line.size)
                val metrics = measure.fontMetrics
                // Normalize control/format characters, including newlines and bidi overrides.
                val clean = line.text.map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }.joinToString("")
                var row = ""
                val points = clean.codePoints().toArray()
                for (point in points) {
                    val next = String(Character.toChars(point))
                    require(metrics.stringWidth(next) <= 360) { "Unrenderable receipt character" }
                    if (metrics.stringWidth(row + next) > 360) {
                        rows.add(line.copy(text = row)); height += metrics.height + 3; row = ""
                        require(height <= maxHeight - metrics.height - 23) { "Receipt exceeds maximum height" }
                    }
                    row += next
                }
                rows.add(line.copy(text = row)); height += metrics.height + 3
                require(height <= maxHeight) { "Receipt exceeds maximum height" }
            }
        } finally { measure.dispose() }
        val image = BufferedImage(384, height, BufferedImage.TYPE_BYTE_BINARY)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE; g.fillRect(0, 0, 384, height)
            g.color = Color.BLACK
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            var y = 10
            for (row in rows) {
                g.font = Font(Font.MONOSPACED, if (row.bold) Font.BOLD else Font.PLAIN, row.size)
                g.drawString(row.text, 12, y + g.fontMetrics.ascent)
                y += g.fontMetrics.height + 3
            }
        } finally { g.dispose() }
        return image
    }
}
