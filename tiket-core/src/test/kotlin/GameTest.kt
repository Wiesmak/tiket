package pl.umamusume.tiket.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.shouldBe
import pl.umamusume.tiket.core.model.Money
import pl.umamusume.tiket.core.model.Player
import pl.umamusume.tiket.core.model.PlayerId
import pl.umamusume.tiket.core.model.Race
import pl.umamusume.tiket.core.model.RaceId

internal class GameTest : FunSpec ({

    val game = Game(
        races = listOf(
            Race(
                RaceId(0),
                name = "Tokyo Daishoten",
                fieldSize = 16,
                distance = 2000,
                group = Race.RaceGroup.G1,
                racecourse = "Tokyo",
                date = "2026-11-01"
            ),
            Race(
                RaceId(1),
                name = "Japan Cup",
                fieldSize = 18,
                distance = 2400,
                group = Race.RaceGroup.G1,
                racecourse = "Tokyo",
                date = "2026-11-29",
            ),
            Race(
                RaceId(2),
                name = "Arima Kinen",
                fieldSize = 16,
                distance = 2500,
                group = Race.RaceGroup.G1,
                racecourse = "Nakayama",
                date = "2026-12-27",
            ),
        ),
        players = listOf(
            Player(PlayerId(0), "John Uma", balance = Money(1000), freebet = Money(100)),
            Player(PlayerId(1), "Tazuna Hayakawa", balance = Money(800), freebet = Money(800)),
            Player(PlayerId(2), "Jo Kitahara", balance = Money(0), freebet = Money(0)),
        ),
    )

    test("game leaderboard should be sorted by balance descending") {
        game.leaderboard() shouldBeSortedWith compareByDescending { it.balance.value }
    }

    test("game should return next race until finished") {
        val races = mutableListOf<Race>()

        while (!game.finished()) {
            races.add(game.currentRace())
            game.nextRace()
        }

        races.size shouldBe 3

        races shouldBeSortedWith compareBy { it.id.value }
    }

})