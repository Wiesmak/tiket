package pl.umamusume.tiket.backend.grpc

import io.grpc.stub.StreamObserver
import org.springframework.grpc.server.service.GrpcService
import pl.umamusume.tiket.backend.application.RaceFinancialApplicationService
import pl.umamusume.tiket.contracts.v1.*

@GrpcService
class ResultGrpcService(private val service: RaceFinancialApplicationService) : ResultServiceGrpc.ResultServiceImplBase() {
    override fun submitResult(r: SubmitResultRequest, o: StreamObserver<ResultAcknowledgement>) = respond(o) { service.submit(r) }
    override fun verifyResult(r: VerifyResultRequest, o: StreamObserver<ResultAcknowledgement>) = respond(o) { service.verify(r) }
    override fun getResult(r: RaceQuery, o: StreamObserver<ResultAcknowledgement>) = respond(o) { service.getResult(r) }
    override fun listResultRevisions(r: ResultRevisionsRequest, o: StreamObserver<ResultRevisions>) = respond(o) { service.revisions(r) }
    override fun settleRace(r: SettleRaceRequest, o: StreamObserver<SettlementInformation>) = respond(o) { service.settle(r) }
    override fun getSettlement(r: SettlementQuery, o: StreamObserver<SettlementInformation>) = respond(o) { service.getSettlement(r) }
    override fun listRaceLegResolutions(r: RaceLegResolutionsRequest, o: StreamObserver<RaceLegResolutions>) = respond(o) { service.legResolutions(r) }
}
