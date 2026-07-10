package pl.umamusume.tiket.core.model

data class Player(
    val id: PlayerId,
    val name: String,
    var balance: Money,
    var freebet: Money,
)