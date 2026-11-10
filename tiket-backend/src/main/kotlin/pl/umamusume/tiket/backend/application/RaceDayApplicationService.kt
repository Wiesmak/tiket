package pl.umamusume.tiket.backend.application

import org.springframework.stereotype.Service
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.raceday.*
import pl.umamusume.tiket.core.validation.getOrThrow

@Service
class RaceDayApplicationService(
    private val days: RaceDayRepository, private val races: RaceRepository,
    private val tx: PersistenceTransactions, private val locks: PersistenceLocks,
    private val commands: AdministrationCommands
) {
    fun create(request: CreateRaceDayRequest): DayAcknowledgement {
        require(request.hasDefinition())
        val definition = request.definition.toDomain()
        return commands.execute("CreateRaceDay", definition.id.value.toString(), request.idempotencyKey,
            request, DayAcknowledgement::parseFrom) {
            days.insert(definition)
            dayAck(definition.id, RaceDayState())
        }
    }
    fun locked(id: RaceDayId): RaceDayState {
        if (days.state(id) == null) throw MissingRecord()
        locks.day(id)
        return days.state(id) ?: throw InvalidPersistenceException("Missing locked day")
    }
    fun definition(id: RaceDayId): RaceDayDefinition = persisted { days.load(id) } ?: throw MissingRecord()
    fun states(day: RaceDayDefinition) = day.races.map {
        persisted { races.state(day.id, it.id) } ?: throw InvalidPersistenceException("Missing race state")
    }
    fun get(request: DayQuery): RaceDayInformation = tx.required {
        val id = RaceDayId(uuid(request.raceDayId))
        val state = locked(id)
        RaceDayInformation.newBuilder().setDefinition(dayConfiguration(definition(id))).setStatus(dayAck(id, state)).build()
    }
    fun transition(request: DayCommand, target: RaceDayPhase): DayAcknowledgement {
        val id = RaceDayId(uuid(request.raceDayId))
        require(request.expectedVersion >= 0)
        require(target in setOf(RaceDayPhase.READY, RaceDayPhase.RUNNING, RaceDayPhase.FINALIZING))
        return commands.execute(target.name, id.value.toString(), request.idempotencyKey, request, DayAcknowledgement::parseFrom) {
            val state = locked(id)
            version(request.expectedVersion, state.version)
            val day = definition(id)
            val next = RaceDayLifecyclePolicy.transition(day, state, target, states(day)).getOrThrow()
            days.update(id, state.version, next)
            dayAck(id, next)
        }
    }
}
