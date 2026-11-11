package pl.umamusume.tiket.backend.security

import io.grpc.Context
import org.springframework.grpc.server.security.GrpcSecurity
import java.security.MessageDigest
import java.util.*

object AuditIdentity {
    val correlation: Context.Key<UUID> = Context.key("tiket-correlation")
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun actor(): String? = GrpcSecurity.SECURITY_CONTEXT_KEY.get()?.authentication?.name?.let(::digest)
}
