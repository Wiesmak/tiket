package pl.umamusume.tiket.backend.grpc

import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.grpc.server.service.GrpcService
import pl.umamusume.tiket.backend.application.*
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.contracts.v1.*
import pl.umamusume.tiket.core.raceday.RaceDayPhase
import pl.umamusume.tiket.core.validation.*
import java.time.DateTimeException

private val logger = org.slf4j.LoggerFactory.getLogger("AdministrationGrpc")
private fun <T> respond(observer: StreamObserver<T>, work: () -> T) {
    try {
        observer.onNext(work())
        observer.onCompleted()
    } catch (e: Exception) {
        val status = when (e) {
            is MissingRecord -> Status.NOT_FOUND
            is StaleVersionException, is ConcurrencyFailureException -> Status.ABORTED
            is DuplicateKeyException -> Status.ALREADY_EXISTS
            is IdempotencyConflictException -> Status.ALREADY_EXISTS
            is CommandPrecondition -> Status.FAILED_PRECONDITION
            is DomainException -> when (e.error.code) {
                ErrorCode.DUPLICATE_OPERATION -> Status.ALREADY_EXISTS
                ErrorCode.INVALID_TRANSITION, ErrorCode.DAY_NOT_RUNNING, ErrorCode.INCONSISTENT_ACCOUNT,
                ErrorCode.ALREADY_SETTLED -> Status.FAILED_PRECONDITION
                else -> Status.INVALID_ARGUMENT
            }
            is IllegalArgumentException, is DateTimeException, is ArithmeticException -> Status.INVALID_ARGUMENT
            else -> Status.INTERNAL
        }
        if (status.code == Status.Code.INTERNAL) logger.error("Administration command failed", e)
        observer.onError(status.withDescription(when (status.code) {
            Status.Code.INTERNAL -> "Internal administration failure"
            Status.Code.NOT_FOUND -> "Requested record not found"
            Status.Code.ABORTED -> "State version conflict"
            Status.Code.ALREADY_EXISTS -> "Identity or idempotency conflict"
            Status.Code.FAILED_PRECONDITION -> "Operation is not permitted in the current state"
            else -> "Invalid request"
        }).asRuntimeException())
    }
}

@GrpcService
class RaceDayGrpcService(private val service: RaceDayApplicationService) : RaceDayServiceGrpc.RaceDayServiceImplBase() {
    override fun createRaceDay(r: CreateRaceDayRequest, o: StreamObserver<DayAcknowledgement>) = respond(o) { service.create(r) }
    override fun getRaceDay(r: DayQuery, o: StreamObserver<RaceDayInformation>) = respond(o) { service.get(r) }
    override fun publishRaceDay(r: DayCommand, o: StreamObserver<DayAcknowledgement>) = respond(o) { service.transition(r, RaceDayPhase.READY) }
    override fun startRaceDay(r: DayCommand, o: StreamObserver<DayAcknowledgement>) = respond(o) { service.transition(r, RaceDayPhase.RUNNING) }
    override fun finalizeRaceDay(r: DayCommand, o: StreamObserver<DayAcknowledgement>) = respond(o) { service.transition(r, RaceDayPhase.FINALIZING) }
}
@GrpcService
class RaceGrpcService(private val service: RaceApplicationService) : RaceServiceGrpc.RaceServiceImplBase() {
    override fun listRaces(r: DayQuery, o: StreamObserver<RaceList>) = respond(o) { service.list(r) }
    override fun getRace(r: RaceQuery, o: StreamObserver<RaceInformation>) = respond(o) { service.get(r) }
    override fun getRaceStatus(r: RaceQuery, o: StreamObserver<RaceAcknowledgement>) = respond(o) { service.get(r).status }
    override fun openBetting(r: RaceCommand, o: StreamObserver<RaceAcknowledgement>) = respond(o) { service.betting(r, true) }
    override fun closeBetting(r: RaceCommand, o: StreamObserver<RaceAcknowledgement>) = respond(o) { service.betting(r, false) }
}
@GrpcService
class EnrollmentGrpcService(private val service: EnrollmentApplicationService) : EnrollmentServiceGrpc.EnrollmentServiceImplBase() {
    override fun enrollPlayer(r: EnrollPlayerRequest, o: StreamObserver<EnrollmentInformation>) = respond(o) { service.enroll(r) }
    override fun addOpeningFunding(r: AddOpeningFundingRequest, o: StreamObserver<EnrollmentInformation>) = respond(o) { service.fund(r) }
    override fun getEnrollment(r: EnrollmentQuery, o: StreamObserver<EnrollmentInformation>) = respond(o) { service.get(r) }
    override fun listPlayers(r: DayQuery, o: StreamObserver<PlayerList>) = respond(o) { service.list(r) }
}
