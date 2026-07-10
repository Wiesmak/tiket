package pl.umamusume.tiket.core

import pl.umamusume.tiket.core.model.Player
import pl.umamusume.tiket.core.model.Race

class Game (
    val races: List<Race>,
    val players: List<Player>,
) {
    var progress = 0
        private set

    fun finished() = progress >= races.size

    fun currentRace() = races[progress]

    fun nextRace() {
        if (!finished()) {
            progress++
        }
    }

    fun leaderboard() = players.sortedByDescending { it.balance.value }
}