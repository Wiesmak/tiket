package pl.umamusume.tiket.image_generator

interface ImageGenerator {
    suspend fun generatePbmTicket(page: TicketPage): ByteArray

    suspend fun generatePngTicket(pge: TicketPage): ByteArray
}