package pl.umamusume.tiket.image_generator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

object TicketImageGenerator : ImageGenerator {

    override suspend fun generatePbmTicket(page: TicketPage): ByteArray {
        val command = generateCommand(page, generatePng = false)
        val imageBytes = executeMagick(command)
        return imageBytes
    }

    override suspend fun generatePngTicket(pge: TicketPage): ByteArray {
        val command = generateCommand(pge, generatePng = true)
        val imageBytes = executeMagick(command)
        return imageBytes
    }

    private fun generateCommand(page: TicketPage, generatePng: Boolean): List<String>  = listOf(
        "magick",
        "-font", "./Monaco.ttf",
        "-fill", "black",
        "-pointsize", "20",
        "(", "-size", "365x30", "-background", "white", "-gravity", "center", "caption:${page.id}", ")",
        "(", "ura.png", "-resize", "200x", "-colorspace", "gray", ")",
        "-pointsize", "15",
        "(", "-size", "365x20", "-background", "white", "-gravity", "center", "caption:${generateRacecourseInfo(page.racecourse, page.dayNumber)}", ")",
        "(", "-size", "365x40", "-background", "white", "-gravity", "center", "caption:${generatePosInfo(page.counter, page.cashier)}", ")",
        "(", "-size", "365x50", "-background", "white", "-gravity", "center", "caption:${generateDateInfo(page.betDate, page.printDate)}", ")",
        "(", "-size", "365x25", "-background", "white", "-gravity", "center", "caption:${generateRaceNumberInfo(page.raceNumber)}", ")",
        "(", "-size", "365x70", "-background", "white", "-gravity", "center", "-interline-spacing", "5", "caption:${generateBetInfo(page.betSize, page.bet, page.betR, page.betValue)}", ")",
        "-pointsize", "18",
        "(", "-size", "365x50", "-background", "white", "-gravity", "center", "caption:Wartość zakładu: ${page.betValue}", ")",
        "-pointsize", "15",
        "(", "-size", "365x80", "-background", "white", "-gravity", "center", "caption:${umapolStr()}", ")",
        "-append",
        if (generatePng) "+write" else "",
        if (generatePng) "out_test.png" else "",
        "pbm:-"
    )

    private suspend fun executeMagick(command: List<String>): ByteArray = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command).start()

        val errorOutputDeferred = async { process.errorStream.bufferedReader().readText() }
        val imageBytesDeferred = async { process.inputStream.readBytes() }

        val exitCode = process.onExit().await().exitValue()

        if (exitCode != 0) {
            val errorMessage = errorOutputDeferred.await()
            throw RuntimeException("ImageMagick failed with code $exitCode: $errorMessage")
        }

        imageBytesDeferred.await()
    }

    private fun generateRacecourseInfo(racecourse: String, dayNumber: String): String {
        val lineLength = 40
        val dayNumberParsed = "0".repeat(3 - dayNumber.length) + dayNumber
        val Day = "Dzień $dayNumberParsed"
        val Tor = "Tor: $racecourse"
        val spacesNeeded = lineLength - (Tor.length + Day.length)
        val spaces = " ".repeat(spacesNeeded)
        return "$Tor$spaces$Day"
    }

    private fun generateMissingSpaces(str: String): String {
        val lineLength = 40
        val spacesNeeded = lineLength - str.length
        return " ".repeat(spacesNeeded)
    }

    private fun generatePosInfo(counter: String, cashier: String): String {
        val counterInfo = "Stanowisko: $counter"
        val cashierInfo = "Kasjer: $cashier"
        return "${counterInfo + generateMissingSpaces(counterInfo)} ${cashierInfo + generateMissingSpaces(cashierInfo)}"
    }

    private fun generateDateInfo(betDate: String, printDate: String): String {
        val betDateInfo = "Data zakładu: $betDate"
        val printDateInfo = "Data wydruku: $printDate"
        return "${betDateInfo + generateMissingSpaces(betDateInfo)} ${printDateInfo + generateMissingSpaces(printDateInfo)}"
    }

    private fun generateRaceNumberInfo(raceNumber: String): String {
        val raceNumberInfo = "Wyścig: $raceNumber"
        return " ".repeat(15) + raceNumberInfo
    }

    private fun generateBetInfo(betSize: String, bet: String, betR: String, betValue: String): String {
        return " ".repeat(15) + "Zakład:\n" + betSize + "x" + betValue + "   " + bet + "\n" + "R: " + betR
    }

    private fun umapolStr(): String {
        return "  ------------------------------------\n" +
                "    DOŁĄCZ DO NAS   UMAMUSUME POLSKA    \n" +
                "          DISCORD - umamusume.pl        \n" +
                "  ------------------------------------"
    }
}