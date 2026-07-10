package pl.umamusume.tiket.app

import pl.umamusume.tiket.core.*
import pl.umamusume.tiket.core.model.Money
import pl.umamusume.tiket.core.model.Player
import pl.umamusume.tiket.core.model.PlayerId
import pl.umamusume.tiket.core.model.Race
import pl.umamusume.tiket.core.model.RaceId
import pl.umamusume.tiket.utils.Printer

fun main() {
    TicketImageGenerator

//    val game = Game(
//        races = listOf(
//            Race(
//                RaceId(0),
//                name = "Tokyo Daishoten",
//                fieldSize = 16,
//                distance = 2000,
//                group = Race.RaceGroup.G1,
//                racecourse = "Tokyo",
//                date = "2026-11-01"
//            ),
//            Race(
//                RaceId(1),
//                name = "Japan Cup",
//                fieldSize = 18,
//                distance = 2400,
//                group = Race.RaceGroup.G1,
//                racecourse = "Tokyo",
//                date = "2026-11-29",
//            ),
//            Race(
//                RaceId(2),
//                name = "Arima Kinen",
//                fieldSize = 16,
//                distance = 2500,
//                group = Race.RaceGroup.G1,
//                racecourse = "Nakayama",
//                date = "2026-12-27",
//            ),
//        ),
//        players = listOf(
//            Player(PlayerId(0), "John Uma", balance = Money(1000), freebet = Money(100)),
//            Player(PlayerId(1), "Tazuna Hayakawa", balance = Money(800), freebet = Money(800)),
//            Player(PlayerId(2), "Jo Kitahara", balance = Money(0), freebet = Money(0)),
//        ),
//    )
//
//    while (!game.finished()) {
//        val current = game.currentRace()
//        val printer = Printer(current.toString())
//        printer.printMessage()
//        game.nextRace()
//    }
//
//    val printer = Printer(game.leaderboard().toString())
//    printer.printMessage()
}
