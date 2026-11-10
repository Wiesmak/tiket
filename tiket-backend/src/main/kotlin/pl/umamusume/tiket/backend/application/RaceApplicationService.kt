package pl.umamusume.tiket.backend.application

import org.springframework.stereotype.Service
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.race.RaceLifecyclePolicy
import pl.umamusume.tiket.core.raceday.*
import pl.umamusume.tiket.core.validation.getOrThrow

@Service
class RaceApplicationService(
    private val days: RaceDayApplicationService, private val races: RaceRepository,
    private val tx: PersistenceTransactions, private val locks: PersistenceLocks,
    private val commands: AdministrationCommands
) {
    fun list(request: DayQuery): RaceList = tx.required {
        val id = RaceDayId(uuid(request.raceDayId))
        days.locked(id)
        val day = days.definition(id)
        RaceList.newBuilder().addAllRaces(day.races.map { race ->
            RaceInformation.newBuilder().setDefinition(raceConfiguration(day, race))
                .setStatus(raceAck(id, persisted { races.state(id, race.id) } ?: throw InvalidPersistenceException("Missing state"))).build()
        }).build()
    }
    fun get(request: RaceQuery): RaceInformation {
        val race = RaceId(uuid(request.raceId))
        return list(DayQuery.newBuilder().setRaceDayId(request.raceDayId).build()).racesList
            .singleOrNull { it.definition.raceId == race.value.toString() } ?: throw MissingRecord()
    }
    fun betting(request: RaceCommand, open: Boolean): RaceAcknowledgement {
        val id = RaceDayId(uuid(request.raceDayId))
        val race = RaceId(uuid(request.raceId))
        require(request.expectedDayVersion >= 0 && request.expectedRaceVersion >= 0)
        return commands.execute(if (open) "OpenBetting" else "CloseBetting", id.value.toString()+"/"+race.value,
            request.idempotencyKey, request, RaceAcknowledgement::parseFrom) {
            val dayState = days.locked(id)
            version(request.expectedDayVersion, dayState.version)
            val day = days.definition(id)
            val definition = day.races.singleOrNull { it.id == race } ?: throw MissingRecord()
            locks.race(id, race)
            val current = persisted { races.state(id, race) } ?: throw InvalidPersistenceException("Missing state")
            version(request.expectedRaceVersion, current.version)
            if (dayState.phase != RaceDayPhase.RUNNING) throw CommandPrecondition("Day not running")
            val next = if (open) RaceDayLifecyclePolicy.openBetting(day, dayState, race, days.states(day)).getOrThrow()
                else RaceLifecyclePolicy.transition(definition, current, pl.umamusume.tiket.core.race.RacePhase.BETTING_CLOSED).getOrThrow()
            races.update(id, current.version, next)
            raceAck(id, next)
        }
    }
}
