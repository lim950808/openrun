package dev.openrun.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class PingController {
    @GetMapping("/api/ping")
    fun ping(): Map<String, Any> = mapOf("status" to "ok", "time" to Instant.now().toString())
}
