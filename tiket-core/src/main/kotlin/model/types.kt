package pl.umamusume.tiket.core.model

import kotlin.uuid.Uuid

@JvmInline value class RaceId(val value: Int)
@JvmInline value class HorseId(val value: Int)
@JvmInline value class PlayerId(val value: Int)
@JvmInline value class TicketId(val value: Uuid)
@JvmInline value class Money(val value: Int)