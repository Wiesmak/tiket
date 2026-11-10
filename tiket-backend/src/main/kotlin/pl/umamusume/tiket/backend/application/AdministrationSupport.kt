package pl.umamusume.tiket.backend.application

import com.google.protobuf.MessageLite
import org.springframework.stereotype.Component
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.*
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.pool.PoolRules
import pl.umamusume.tiket.core.race.RaceDefinition
import pl.umamusume.tiket.core.race.RaceRunner
import pl.umamusume.tiket.core.raceday.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest

class MissingRecord : RuntimeException()
class CommandPrecondition(message: String) : RuntimeException(message)
fun uuid(text: String): UUID {
    require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(text)) { "Invalid UUID" }
    return UUID.fromString(text)
}
fun version(expected: Long, actual: Long) {
    require(expected >= 0) { "Negative expected version" }
    if (expected != actual) throw StaleVersionException()
}

@Component
class AdministrationCommands(private val idempotency: IdempotencyRepository) {
    // Proto schemas have no maps. Known fields serialize in field order; repeated order is significant.
    fun <T : MessageLite> execute(operation: String, scope: String, key: String, request: MessageLite,
                                parse: (ByteArray) -> T, work: () -> T): T {
        require(key.isNotBlank()) { "Idempotency key required" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(request.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val result = idempotency.execute(CommandKey(operation, scope, key), fingerprint) {
            Base64.getEncoder().encodeToString(work().toByteArray())
        }
        return parse(Base64.getDecoder().decode(result))
    }
}

fun RaceDayConfiguration.toDomain(): RaceDayDefinition {
    require(noWinnerPolicy == NoWinnerPolicy.HOUSE_RETAINS_POOL) { "Unsupported no-winner policy" }
    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(eventDate)) { "ISO event date required" }
    require(timeZone in ZoneId.getAvailableZoneIds()) { "IANA time zone required" }
    val definitions = racesList.map { r ->
        require(r.title.isNotBlank() && r.runnersList.all { it.programNumber.isNotBlank() && it.displayName.isNotBlank() })
        RaceDefinition(RaceId(uuid(r.raceId)), r.number, r.title,
            r.runnersList.map { RaceRunner(HorseId(uuid(it.horseId)), it.programNumber, it.displayName) }, r.racecourseName)
    }
    val pools = linkedMapOf<PoolKey, PoolRules>()
    racesList.forEach { r ->
        require(r.poolsCount == 4 && r.poolsList.map { it.type }.toSet() == setOf(PoolType.WIN, PoolType.QUINELLA, PoolType.EXACTA, PoolType.TRIFECTA)) { "Exactly four distinct pools required" }
        r.poolsList.forEach { p ->
            pools[PoolKey(RaceId(uuid(r.raceId)), BetType.valueOf(p.type.name))] = PoolRules(Money(p.unitStake), TakeRate(p.takeBasisPoints))
        }
    }
    return RaceDayDefinition(RaceDayId(uuid(raceDayId)), LocalDate.parse(eventDate), currency, definitions,
        rulesVersion, pools, RaceDayRules(maximumCouponLegs, maximumOpenRaces,
            pl.umamusume.tiket.core.pool.NoWinnerPolicy.HOUSE_RETAINS_POOL), displayNumber, ZoneId.of(timeZone))
}
fun dayAck(id: RaceDayId, state: RaceDayState): DayAcknowledgement = DayAcknowledgement.newBuilder()
    .setRaceDayId(id.value.toString()).setPhase(DayPhase.valueOf(state.phase.name)).setVersion(state.version).build()
fun raceAck(day: RaceDayId, state: pl.umamusume.tiket.core.race.RaceState): RaceAcknowledgement = RaceAcknowledgement.newBuilder()
    .setRaceDayId(day.value.toString()).setRaceId(state.raceId.value.toString())
    .setPhase(RacePhase.valueOf(state.phase.name)).setVersion(state.version)
    .addAllScratchedHorseIds(state.scratched.map { it.value.toString() }.sorted())
    .setSelectedResultRevision(state.result?.revision ?: "").build()
fun raceConfiguration(day: RaceDayDefinition, race: RaceDefinition): RaceConfiguration =
    RaceConfiguration.newBuilder().setRaceId(race.id.value.toString()).setNumber(race.number)
        .setTitle(race.title).setRacecourseName(race.racecourseName)
        .addAllRunners(race.runners.map { Runner.newBuilder().setHorseId(it.horseId.value.toString())
            .setProgramNumber(it.programNumber).setDisplayName(it.displayName).build() })
        .addAllPools(BetType.entries.map { type ->
            val rules = day.pools.getValue(PoolKey(race.id, type))
            PoolConfiguration.newBuilder().setType(PoolType.valueOf(type.name))
                .setUnitStake(rules.unitStake.amount).setTakeBasisPoints(rules.takeRate.basisPoints).build()
        }).build()
fun dayConfiguration(day: RaceDayDefinition): RaceDayConfiguration = RaceDayConfiguration.newBuilder()
    .setRaceDayId(day.id.value.toString()).setEventDate(day.date.toString()).setDisplayNumber(day.displayNumber)
    .setCurrency(day.currency).setTimeZone(day.timeZone.id).setRulesVersion(day.rulesVersion)
    .setMaximumCouponLegs(day.rules.maximumCouponLegs).setMaximumOpenRaces(day.rules.maximumOpenRaces)
    .setNoWinnerPolicy(NoWinnerPolicy.HOUSE_RETAINS_POOL)
    .addAllRaces(day.races.map { raceConfiguration(day, it) }).build()

/** Domain reconstruction failures describe stored corruption, not malformed client input. */
fun <T> persisted(read: () -> T): T = try { read() }
catch (e: pl.umamusume.tiket.core.validation.DomainException) {
    throw InvalidPersistenceException("Stored domain record failed reconstruction")
}
catch (e: ArithmeticException) { throw InvalidPersistenceException("Stored monetary overflow") }
catch (e: IllegalArgumentException) { throw InvalidPersistenceException("Invalid stored record") }
