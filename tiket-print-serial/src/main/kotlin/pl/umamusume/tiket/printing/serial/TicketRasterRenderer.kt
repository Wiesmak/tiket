package pl.umamusume.tiket.printing.serial

import pl.umamusume.tiket.printing.TicketPrintData
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

class TicketRasterRenderer {
    enum class Alignment { LEFT, CENTER, RIGHT }

    data class Line(
        val text: String,
        val size: Int,
        val bold: Boolean,
        val alignment: Alignment = Alignment.CENTER,
        val rightText: String? = null
    )

    private data class Section(val lines: List<Line>, val minHeight: Int, val spacing: Int = 0)
    private data class Row(val line: Line, val height: Int)
    private data class Block(val rows: List<Row>, val height: Int, val spacing: Int)

    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    // Preserve the legacy sections and 200px-wide logo, with larger bold text
    // for thermal legibility. Sections expand to fit measured rows.
    private fun sections(d: TicketPrintData): List<Section> {
        fun line(text: String, size: Int = 18, alignment: Alignment = Alignment.CENTER, right: String? = null) =
            Line(text, size, true, alignment, right)
        val id = d.ticketId.toString().replace("-", "").takeLast(16).chunked(4).joinToString(" ")
        val code = when (d.betType) {
            "WIN" -> "ZWC"
            "QUINELLA" -> "PDK"
            "EXACTA" -> "DWJ"
            "TRIFECTA" -> "TRJ"
            else -> error("Unsupported bet type")
        }
        return listOf(
            Section(listOf(line(id, 24)), 36),
            Section(listOf(line("Tor: ${d.racecourseName}", alignment = Alignment.LEFT, right = "Dzień ${d.raceDayNumber}")), 20),
            Section(listOf(
                line("Stanowisko: POS (UMA${d.pointOfSaleNumber.toString().padStart(3, '0')})", alignment = Alignment.LEFT),
                line("Kasjer: ${d.cashierIdentifier}", alignment = Alignment.LEFT)
            ), 40),
            Section(listOf(
                line("Data zakładu: ${date.format(d.betDateTime)}", alignment = Alignment.LEFT),
                line("Data wydruku: ${date.format(d.printDateTime)}", alignment = Alignment.LEFT)
            ), 50),
            Section(listOf(line("Wyścig: ${d.raceNumber}")), 25),
            Section(listOf(
                line("Zakład:"),
                line("${d.unitsPerCombination}x${d.unitBetPrice} ${d.currency}   $code ${d.betString}"),
                line("R: ${d.totalBetUnits}")
            ), 70, 5),
            Section(listOf(line("Wartość zakładu: ${d.totalBetValue} ${d.currency}", 20)), 50),
            Section(listOf(
                line("------------------------------------", 15),
                line("DOŁĄCZ DO NAS   UMAMUSUME POLSKA", 16),
                line("DISCORD - umamusume.pl", 16),
                line("------------------------------------", 15)
            ), 80, 5)
        )
    }

    fun content(d: TicketPrintData): List<Line> = sections(d).flatMap { it.lines }

    fun render(d: TicketPrintData, maxHeight: Int = 4000): BufferedImage {
        require(maxHeight in 256..20000) { "Invalid receipt height limit" }
        require(listOf(d.racecourseName, d.cashierIdentifier, d.betString, d.currency)
            .sumOf { it.length.toLong() } <= 30000) { "Receipt content exceeds safe limit" }
        val source = sections(d)
        require(source.sumOf { s -> s.lines.sumOf { it.text.length.toLong() + (it.rightText?.length ?: 0) } } <= 32000) {
            "Receipt content exceeds safe limit"
        }
        val scratch = BufferedImage(WIDTH, 1, BufferedImage.TYPE_BYTE_BINARY)
        val measure = scratch.createGraphics()
        val blocks: List<Block>
        var height = MARGIN * 2 + logo.height
        try {
            blocks = source.map { section ->
                val rows = section.lines.flatMap { line ->
                    measure.font = font(line)
                    val metrics = measure.fontMetrics
                    val left = clean(line.text)
                    val right = line.rightText?.let(::clean)
                    if (right != null && metrics.stringWidth(left) + metrics.stringWidth(right) + metrics.charWidth(' ') <= CONTENT_WIDTH) {
                        listOf(Row(line.copy(text = left, rightText = right), metrics.height))
                    } else {
                        val leftRows = wrap(left, metrics).map { Row(line.copy(text = it, rightText = null), metrics.height) }
                        val rightRows = right?.let { text ->
                            wrap(text, metrics).map { Row(line.copy(text = it, rightText = null, alignment = Alignment.RIGHT), metrics.height) }
                        }.orEmpty()
                        leftRows + rightRows
                    }
                }
                val blockHeight = maxOf(section.minHeight, rows.sumOf { it.height } + section.spacing * (rows.size - 1))
                height += blockHeight
                require(height <= maxHeight) { "Receipt exceeds maximum height" }
                Block(rows, blockHeight, section.spacing)
            }
        } finally {
            measure.dispose()
        }
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_BYTE_BINARY)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, WIDTH, height)
            g.color = Color.BLACK
            // Paint opaque black glyphs directly onto the 1-bit canvas. No
            // antialiasing, grayscale text intermediates, or dithering.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            var y = MARGIN
            blocks.forEachIndexed { index, block ->
                val textHeight = block.rows.sumOf { it.height } + block.spacing * (block.rows.size - 1)
                var rowY = y + (block.height - textHeight) / 2
                for (row in block.rows) {
                    g.font = font(row.line)
                    val metrics = g.fontMetrics
                    val x = when (row.line.alignment) {
                        Alignment.LEFT -> SIDE_MARGIN
                        Alignment.CENTER -> (WIDTH - metrics.stringWidth(row.line.text)) / 2
                        Alignment.RIGHT -> WIDTH - SIDE_MARGIN - metrics.stringWidth(row.line.text)
                    }
                    g.drawString(row.line.text, x, rowY + metrics.ascent)
                    row.line.rightText?.let {
                        g.drawString(it, WIDTH - SIDE_MARGIN - metrics.stringWidth(it), rowY + metrics.ascent)
                    }
                    rowY += row.height + block.spacing
                }
                y += block.height
                if (index == 0) {
                    g.drawImage(logo, (WIDTH - logo.width) / 2, y, null)
                    y += logo.height
                }
            }
        } finally {
            g.dispose()
        }
        return image
    }

    private fun font(line: Line) = Font(fontFamily, if (line.bold) Font.BOLD else Font.PLAIN, line.size)

    private fun clean(text: String) = text.map {
        if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it
    }.joinToString("")

    private fun wrap(text: String, metrics: FontMetrics): List<String> {
        val rows = mutableListOf<String>()
        var row = StringBuilder()
        for (point in text.codePoints().toArray()) {
            val next = String(Character.toChars(point))
            require(metrics.stringWidth(next) <= CONTENT_WIDTH) { "Unrenderable receipt character" }
            if (metrics.stringWidth(row.toString() + next) > CONTENT_WIDTH) {
                rows.add(row.toString())
                row = StringBuilder()
            }
            row.append(next)
        }
        rows.add(row.toString())
        return rows
    }

    private companion object {
        const val WIDTH = 384
        const val SIDE_MARGIN = 12
        const val CONTENT_WIDTH = WIDTH - SIDE_MARGIN * 2
        const val MARGIN = 8

        val fontFamily: String by lazy {
            val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
            listOf("DejaVu Sans Mono", "Lucida Console", "Consolas").firstOrNull { it in available }
                ?: Font.MONOSPACED
        }

        val logo: BufferedImage by lazy {
            val source = checkNotNull(TicketRasterRenderer::class.java.getResourceAsStream("/ura.png")) {
                "Missing ticket logo resource: ura.png"
            }.use { checkNotNull(ImageIO.read(it)) { "Invalid ticket logo resource" } }
            val height = (source.height * 200.0 / source.width).roundToInt()
            // Match the legacy grayscale resize before converting to printer monochrome.
            val scaled = BufferedImage(200, height, BufferedImage.TYPE_BYTE_GRAY)
            val g = scaled.createGraphics()
            try {
                g.color = Color.WHITE
                g.fillRect(0, 0, scaled.width, scaled.height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(source, 0, 0, scaled.width, scaled.height, null)
            } finally {
                g.dispose()
            }
            scaled
        }
    }
}
