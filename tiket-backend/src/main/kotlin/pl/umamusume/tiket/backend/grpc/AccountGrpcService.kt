package pl.umamusume.tiket.backend.grpc

import io.grpc.stub.StreamObserver
import org.springframework.grpc.server.service.GrpcService
import pl.umamusume.tiket.backend.application.AccountApplicationService
import pl.umamusume.tiket.contracts.v1.*

@GrpcService
class AccountGrpcService(private val service: AccountApplicationService) : AccountServiceGrpc.AccountServiceImplBase() {
    override fun getAccount(r: AccountQuery, o: StreamObserver<AccountInformation>) = respond(o) { service.get(r) }
    override fun getWithdrawalQuote(r: AccountQuery, o: StreamObserver<WithdrawalQuoteInformation>) = respond(o) { service.quote(r) }
    override fun cashOut(r: CashOutRequest, o: StreamObserver<CashoutReceipt>) = respond(o) { service.cashOut(r) }
    override fun listAccountMovements(r: AccountMovementsRequest, o: StreamObserver<AccountMovements>) = respond(o) { service.movements(r) }
    override fun getCashoutReceipt(r: AccountQuery, o: StreamObserver<CashoutReceipt>) = respond(o) { service.receipt(r) }
}
