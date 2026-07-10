package pl.umamusume.tiket.image_generator

data class TicketPage (
    val id: String,

    val dayNumber: String,
    val racecourse: String,
    val counter: String,
    val cashier: String,
    val betDate: String,
    val printDate: String,

    val raceNumber: String,

    val betSize: String,
    val bet: String,
    val betR: String,

    val betValue: String,
)