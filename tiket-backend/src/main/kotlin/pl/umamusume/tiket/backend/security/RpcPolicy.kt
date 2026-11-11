package pl.umamusume.tiket.backend.security

/** Full service/method identity, never suffix-only matching. Unknown RPCs fail closed. */
object RpcPolicy {
    val roles = setOf(
        "EVENT_ADMIN",
        "CASHIER",
        "RESULT_SUBMITTER",
        "RESULT_VERIFIER",
        "SETTLEMENT_OPERATOR",
        "AUDITOR",
        "PLAYER"
    )
    val methods = mapOf(
        "RaceDayService/CreateRaceDay" to setOf("EVENT_ADMIN"),
        "RaceDayService/GetRaceDay" to setOf("READ"),
        "RaceDayService/PublishRaceDay" to setOf("EVENT_ADMIN"),
        "RaceDayService/StartRaceDay" to setOf("EVENT_ADMIN"),
        "RaceDayService/FinalizeRaceDay" to setOf("EVENT_ADMIN"),
        "RaceDayService/CompleteRaceDay" to setOf("EVENT_ADMIN"),
        "RaceDayService/CloseRaceDay" to setOf("EVENT_ADMIN"),
        "RaceService/ScratchHorse" to setOf("EVENT_ADMIN"),
        "RaceService/VoidRace" to setOf("EVENT_ADMIN"),
        "RaceService/ListRaces" to setOf("READ"),
        "RaceService/GetRace" to setOf("READ"),
        "RaceService/GetRaceStatus" to setOf("READ"),
        "RaceService/OpenBetting" to setOf("EVENT_ADMIN"),
        "RaceService/CloseBetting" to setOf("EVENT_ADMIN"),
        "EnrollmentService/EnrollPlayer" to setOf("CASHIER"),
        "EnrollmentService/AddOpeningFunding" to setOf("CASHIER"),
        "EnrollmentService/GetEnrollment" to setOf("ACCOUNT"),
        "EnrollmentService/ListPlayers" to setOf("AUDITOR"),
        "BettingService/QuoteCoupon" to setOf("CASHIER", "PLAYER"),
        "BettingService/PurchaseCoupon" to setOf("CASHIER"),
        "BettingService/GetCoupon" to setOf("ACCOUNT"),
        "BettingService/ListPlayerCoupons" to setOf("ACCOUNT"),
        "PrintingService/GetPrintStatus" to setOf("CASHIER"),
        "PrintingService/ReprintCoupon" to setOf("CASHIER"),
        "ResultService/SubmitResult" to setOf("RESULT_SUBMITTER"),
        "ResultService/VerifyResult" to setOf("RESULT_VERIFIER"),
        "ResultService/GetResult" to setOf("READ"),
        "ResultService/ListResultRevisions" to setOf("RESULT_SUBMITTER", "RESULT_VERIFIER", "AUDITOR"),
        "ResultService/SettleRace" to setOf("SETTLEMENT_OPERATOR"),
        "ResultService/GetSettlement" to setOf("AUDITOR"),
        "ResultService/ListRaceLegResolutions" to setOf("AUDITOR"),
        "AccountService/GetAccount" to setOf("ACCOUNT"),
        "AccountService/GetWithdrawalQuote" to setOf("ACCOUNT"),
        "AccountService/CashOut" to setOf("CASHIER"),
        "AccountService/ListAccountMovements" to setOf("ACCOUNT"),
        "AccountService/GetCashoutReceipt" to setOf("ACCOUNT"),
        "SystemService/Ping" to setOf("PUBLIC")
    ).mapKeys { "pl.umamusume.tiket.api.v1." + it.key }
}
