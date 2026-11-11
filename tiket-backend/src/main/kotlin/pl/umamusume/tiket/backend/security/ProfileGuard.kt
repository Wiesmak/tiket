package pl.umamusume.tiket.backend.security

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class ProfileGuard(env: Environment) {
    init {
        require(!("production" in env.activeProfiles && "test" in env.activeProfiles)) {
            "Production cannot enable test security"
        }
    }
}
