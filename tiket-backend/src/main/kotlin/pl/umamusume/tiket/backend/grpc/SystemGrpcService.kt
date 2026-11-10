package pl.umamusume.tiket.backend.grpc

import io.grpc.stub.StreamObserver
import org.springframework.grpc.server.service.GrpcService
import pl.umamusume.tiket.contracts.v1.PingRequest
import pl.umamusume.tiket.contracts.v1.PingResponse
import pl.umamusume.tiket.contracts.v1.SystemServiceGrpc

@GrpcService
class SystemGrpcService : SystemServiceGrpc.SystemServiceImplBase() {
    override fun ping(request: PingRequest, responseObserver: StreamObserver<PingResponse>) {
        responseObserver.onNext(PingResponse.newBuilder().setStatus("OK").setApiVersion("v1").build())
        responseObserver.onCompleted()
    }
}
