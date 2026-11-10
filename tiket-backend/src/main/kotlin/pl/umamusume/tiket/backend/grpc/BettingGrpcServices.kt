package pl.umamusume.tiket.backend.grpc

import io.grpc.stub.StreamObserver
import org.springframework.grpc.server.service.GrpcService
import pl.umamusume.tiket.backend.application.BettingApplicationService
import pl.umamusume.tiket.backend.application.PrintOutbox
import pl.umamusume.tiket.contracts.v1.*

@GrpcService
class BettingGrpcService(private val service: BettingApplicationService) : BettingServiceGrpc.BettingServiceImplBase() {
    override fun quoteCoupon(r: QuoteCouponRequest, o: StreamObserver<QuoteCouponResponse>) =
        respond(o) { service.quote(r) }

    override fun purchaseCoupon(r: PurchaseCouponRequest, o: StreamObserver<PurchaseCouponResponse>) =
        respond(o) { service.purchase(r) }

    override fun getCoupon(r: CouponQuery, o: StreamObserver<CouponInformation>) = respond(o) { service.get(r) }
    override fun listPlayerCoupons(r: ListPlayerCouponsRequest, o: StreamObserver<CouponList>) =
        respond(o) { service.list(r) }
}

@GrpcService
class PrintingGrpcService(private val service: PrintOutbox) : PrintingServiceGrpc.PrintingServiceImplBase() {
    override fun getPrintStatus(r: PrintStatusQuery, o: StreamObserver<PrintStatus>) = respond(o) { service.status(r) }
    override fun reprintCoupon(r: ReprintCouponRequest, o: StreamObserver<PrintStatus>) =
        respond(o) { service.reprint(r) }
}

