package pl.umamusume.tiket.backend.security

import io.grpc.*
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.grpc.server.GlobalServerInterceptor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Component
@GlobalServerInterceptor
@Order(-10000)
class OperationalInterceptor(
    private val metrics: MeterRegistry,
    private val jdbc: JdbcTemplate,
    manager: PlatformTransactionManager,
    @org.springframework.beans.factory.annotation.Value("\${tiket.limits.concurrent:32}") concurrency: Int
) : ServerInterceptor {
    @Volatile
    private var stopping = false

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent::class)
    fun stopAccepting() {
        stopping = true
    }

    private val permits = Semaphore(concurrency.also { require(it in 1..10000) })
    private val independent = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        timeout = 5
    }
    private val log = LoggerFactory.getLogger(javaClass)
    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>, headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        if (stopping) {
            call.close(Status.UNAVAILABLE.withDescription("Server draining"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        val raw = call.methodDescriptor.fullMethodName
        val method = if (raw in RpcPolicy.methods) raw else "unknown"
        val correlation = UUID.randomUUID()
        val start = System.nanoTime()
        val target = java.util.concurrent.atomic.AtomicReference("undisclosed")
        val digest = java.util.concurrent.atomic.AtomicReference("")
        if (!permits.tryAcquire()) {
            metrics.counter("tiket.grpc.overload").increment()
            call.close(Status.RESOURCE_EXHAUSTED, Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        val released = java.util.concurrent.atomic.AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) permits.release()
        }

        val wrapped = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                try {
                    val outcome = status.code.name
                    metrics.counter("tiket.grpc.requests", "method", method, "status", outcome).increment()
                    metrics.timer("tiket.grpc.duration", "method", method, "status", outcome)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
                    val denial = status.code in setOf(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED)
                    val financialRead = status.isOk && (method.substringAfterLast('/').startsWith("Get") ||
                            method.substringAfterLast('/').startsWith("List")) && AuditIdentity.actor() != null
                    if (!status.isOk || financialRead) {
                        try {
                            independent.executeWithoutResult {
                                jdbc.update(
                                    "INSERT INTO operational_audit(id,actor,operation,resource_scope,correlation_id,request_digest,outcome,failure_category) VALUES (?,?,?,?,?,?,?,?)",
                                    UUID.randomUUID(),
                                    AuditIdentity.actor() ?: "anonymous",
                                    method,
                                    target.get(),
                                    correlation,
                                    digest.get(),
                                    if (denial) "DENIED" else if (status.isOk) "COMMITTED" else if (status.code in setOf(
                                            Status.Code.INVALID_ARGUMENT,
                                            Status.Code.FAILED_PRECONDITION,
                                            Status.Code.ALREADY_EXISTS,
                                            Status.Code.RESOURCE_EXHAUSTED
                                        )
                                    ) "REJECTED" else "FAILED",
                                    if (status.isOk) "" else outcome
                                )
                            }
                        } catch (e: Exception) {
                            log.error(
                                "Security audit persistence unavailable correlation={} category={}",
                                correlation,
                                e.javaClass.simpleName
                            )
                        }
                    }
                    MDC.put("request_id", correlation.toString())
                    log.atInfo().addKeyValue("grpc_method", method).addKeyValue("classification", outcome)
                        .addKeyValue("duration_nanos", System.nanoTime() - start).log("grpc_completed")
                } finally {
                    MDC.remove("request_id")
                    release()
                    super.close(status, trailers)
                }
            }
        }
        val listener = try {
            Contexts.interceptCall(
                Context.current().withValue(AuditIdentity.correlation, correlation),
                wrapped,
                headers,
                next
            )
        } catch (e: Exception) {
            release(); throw e
        }
        return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            override fun onMessage(message: ReqT) {
                if (message is com.google.protobuf.Message) {
                    val selectors = sortedMapOf<String, String>()
                    fun inspect(m: com.google.protobuf.Message) {
                        m.allFields.forEach { (field, value) ->
                            if (value is com.google.protobuf.Message) inspect(value)
                            else if (field.name in setOf(
                                    "race_day_id",
                                    "race_id",
                                    "player_id",
                                    "coupon_id",
                                    "settlement_id",
                                    "print_job_id"
                                )
                            ) {
                                runCatching { UUID.fromString(value.toString()).toString() }.getOrNull()
                                    ?.let { selectors[field.name] = it }
                            }
                        }
                    }
                    inspect(message)
                    if (selectors.isNotEmpty()) target.set(selectors.entries.joinToString("/") { it.key + "=" + it.value })
                    digest.set(
                        java.security.MessageDigest.getInstance("SHA-256").digest(message.toByteArray())
                            .joinToString("") { "%02x".format(it) })
                }
                MDC.put("request_id", correlation.toString())
                try {
                    super.onMessage(message)
                } finally {
                    MDC.remove("request_id")
                }
            }

            override fun onHalfClose() {
                MDC.put("request_id", correlation.toString())
                try {
                    super.onHalfClose()
                } finally {
                    MDC.remove("request_id")
                }
            }

            override fun onCancel() {
                try {
                    super.onCancel()
                } finally {
                    release()
                }
            }

            override fun onComplete() {
                try {
                    super.onComplete()
                } finally {
                    release()
                }
            }
        }
    }
}
