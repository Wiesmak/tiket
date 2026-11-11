package pl.umamusume.tiket.backend.security

import com.google.protobuf.Message
import io.grpc.*
import org.springframework.core.annotation.Order
import org.springframework.grpc.server.GlobalServerInterceptor
import org.springframework.grpc.server.security.GrpcSecurity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.*

/** Checks decoded message selectors before forwarding any message to the business listener. */
@Component
@org.springframework.context.annotation.Profile("!test")
@GlobalServerInterceptor
@Order(1000)
class ResourceAuthorization(private val jdbc: JdbcTemplate, private val env: org.springframework.core.env.Environment) :
    ServerInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        if (method == "pl.umamusume.tiket.api.v1.SystemService/Ping") return next.startCall(call, headers)
        val listener = next.startCall(call, headers)
        return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            private var denied = false
            override fun onMessage(message: ReqT) {
                try {
                    val jwt = GrpcSecurity.SECURITY_CONTEXT_KEY.get()?.authentication?.principal as? Jwt
                        ?: throw Status.UNAUTHENTICATED.asRuntimeException()
                    authorize(method, message as Message, jwt)
                    super.onMessage(message)
                } catch (e: Exception) {
                    denied = true
                    call.close(if (e is StatusRuntimeException) e.status else Status.PERMISSION_DENIED, Metadata())
                }
            }

            override fun onHalfClose() {
                if (!denied) super.onHalfClose()
            }
        }
    }

    private var window = 0L
    private val requests = mutableMapOf<String, Int>()
    @Synchronized
    private fun rate(jwt: Jwt) {
        val now = System.nanoTime() / 1_000_000_000
        if (window != now) {
            window = now; requests.clear()
        }
        val actor = AuditIdentity.digest(jwt.subject ?: deny())
        val count = requests[actor] ?: 0
        val maximum = env.getProperty("tiket.limits.requests-per-second", Int::class.java, 60)
        if (maximum < 1 || count >= maximum || (actor !in requests && requests.size >= 4096))
            throw Status.RESOURCE_EXHAUSTED.withDescription("Request limit reached").asRuntimeException()
        requests[actor] = count + 1
    }

    private fun deny(): Nothing = throw Status.PERMISSION_DENIED.withDescription("Access denied").asRuntimeException()
    private fun claim(jwt: Jwt, name: String): Set<String> =
        (jwt.claims[env.getProperty("tiket.security.claims.$name", name)] as? Collection<*>)?.filterIsInstance<String>()
            ?.toSet() ?: emptySet()

    private fun fields(message: Message): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        message.allFields.forEach { (field, value) ->
            if (value is Message) result.putAll(fields(value))
            else if (!field.isRepeated) result[field.name] = value
        }
        return result
    }

    internal fun authorize(method: String, message: Message, jwt: Jwt) {
        rate(jwt)
        val policy = RpcPolicy.methods[method] ?: deny()
        val roles = claim(jwt, "tiket_roles").intersect(RpcPolicy.roles)
        val accepted = when {
            "READ" in policy -> roles.isNotEmpty()
            "ACCOUNT" in policy -> roles.any { it in setOf("CASHIER", "AUDITOR", "PLAYER") }
            else -> roles.any { it in policy }
        }
        if (!accepted) deny()
        val f = fields(message)
        var day = f["race_day_id"]?.toString() ?: ""
        var player = f["player_id"]?.toString()
        var pos: String? = null
        val coupon = f["coupon_id"]?.toString()
        val job = f["print_job_id"]?.toString()
        if (coupon != null && !method.endsWith("/PurchaseCoupon") || job != null) {
            val rows = if (job != null) jdbc.queryForList(
                "SELECT c.race_day_id,c.player_id,c.pos_number FROM coupon c JOIN print_job j ON j.coupon_id=c.id WHERE j.id=?",
                UUID.fromString(job)
            ) else jdbc.queryForList(
                "SELECT race_day_id,player_id,pos_number FROM coupon WHERE id=?",
                UUID.fromString(coupon)
            )
            val row = rows.singleOrNull() ?: deny()
            val storedDay = row["race_day_id"].toString()
            if (day.isNotEmpty() && day != storedDay) deny()
            day = storedDay
            player = row["player_id"].toString()
            pos = row["pos_number"].toString()
        }
        if (day.isEmpty() || day !in claim(jwt, "tiket_events")) deny()
        // No global or wildcard grants. Cashiers have an explicit per-player mandate.
        if (player != null) {
            val staff = ("ACCOUNT" in policy && "AUDITOR" in roles) || ("CASHIER" in roles && player in claim(
                jwt,
                "tiket_players"
            ))
            val owner = "ACCOUNT" in policy && "PLAYER" in roles && player == jwt.claims[env.getProperty(
                "tiket.security.claims.tiket_player",
                "tiket_player"
            )]
            if (!staff && !owner) deny()
        }
        if (pos != null && "CASHIER" in roles && pos !in claim(jwt, "tiket_pos")) deny()
        if (method.endsWith("/PurchaseCoupon")) {
            if (f["cashier_identifier"] != jwt.claims[env.getProperty(
                    "tiket.security.claims.tiket_cashier",
                    "tiket_cashier"
                )] ||
                f["point_of_sale_number"]?.toString() !in claim(jwt, "tiket_pos")
            ) deny()
        }
    }
}
