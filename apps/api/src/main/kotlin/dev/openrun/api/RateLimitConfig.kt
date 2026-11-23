package dev.openrun.api

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.PathContainer
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.pattern.PathPatternParser
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Public API rate limit (ADR-0010). Defaults are the numbers in docs/requirements.md §4. */
@ConfigurationProperties("openrun.ratelimit")
data class RateLimitProperties(
    /** Off (local, CI, runners): the interceptor is not registered at all. The demo server turns it on. */
    val enabled: Boolean = false,
    val ip: Limit = Limit(perSecond = 50, burst = 100),
    val caller: Limit = Limit(perSecond = 1, burst = 3),
    /** Keys unused this long are dropped by the sweep. */
    val idleEvictAfter: Duration = Duration.ofMinutes(10),
    /** Above this many keys the whole store is cleared (fail-open). */
    val maxKeys: Int = 100_000,
) {
    data class Limit(val perSecond: Long, val burst: Long)
}

/**
 * Two token-bucket policies on [PATH_PATTERN] minus [EXCLUDED_PATTERNS] (ADR-0010 §1). `ip`: every request the interceptor sees, keyed by
 * `remoteAddr` (the client IP from `X-Forwarded-For` only under `server.forward-headers-strategy=framework`).
 * `caller`: `GET` [QUEUE_ME], keyed by the Bearer token string, else `X-Member-Id`, else skipped (the controller
 * answers 401). Rejections go through [ApiErrors.respond] (429 `RATE_LIMITED_IP` / `RATE_LIMITED_CALLER`, counted in
 * `openrun.api.errors{code}`) with `Retry-After` in whole seconds. Buckets live in process memory; idle keys are swept
 * every minute and the whole store is cleared past `max-keys` (fail-open).
 */
@Component
@ConditionalOnProperty("openrun.ratelimit.enabled", havingValue = "true")
class RateLimitInterceptor(
    private val props: RateLimitProperties,
    private val errors: ApiErrors,
    private val json: ObjectMapper,
) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val buckets = ConcurrentHashMap<String, Entry>()
    private val queueMe = PathPatternParser.defaultInstance.parse(QUEUE_ME)

    private class Entry(val bucket: Bucket) {
        @Volatile
        var lastSeenNanos: Long = System.nanoTime()
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (!allowed(response, "ip:" + request.remoteAddr, props.ip, "RATE_LIMITED_IP")) return false
        if (request.method == HttpMethod.GET.name() && queueMe.matches(PathContainer.parsePath(request.requestURI))) {
            val key = request.getHeader(HttpHeaders.AUTHORIZATION)?.let(CallerIdentity::bearer)
                ?: request.getHeader(MEMBER_HEADER)?.ifBlank { null }
            if (key != null && !allowed(response, "caller:$key", props.caller, "RATE_LIMITED_CALLER")) return false
        }
        return true
    }

    private fun allowed(response: HttpServletResponse, key: String, limit: RateLimitProperties.Limit, code: String): Boolean {
        val probe = entryFor(key, limit).bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) return true

        val retryAfterSeconds = maxOf(1L, (probe.nanosToWaitForRefill + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
        val rejection = errors.respond(HttpStatus.TOO_MANY_REQUESTS, code)
        response.status = rejection.statusCode.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(json.writeValueAsString(rejection.body))
        return false
    }

    private fun entryFor(key: String, limit: RateLimitProperties.Limit): Entry {
        buckets[key]?.let {
            it.lastSeenNanos = System.nanoTime()
            return it
        }
        if (buckets.size >= props.maxKeys) clearAll()
        return buckets.computeIfAbsent(key) { Entry(newBucket(limit)) }
    }

    private fun newBucket(limit: RateLimitProperties.Limit): Bucket =
        Bucket.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(limit.burst)
                    .refillGreedy(limit.perSecond, Duration.ofSeconds(1))
                    .build(),
            )
            .build()

    /** Drops keys idle longer than `idle-evict-after`; clears everything if the store is still over `max-keys`. */
    @Scheduled(fixedDelayString = "60000")
    fun sweep() {
        val cutoff = System.nanoTime() - props.idleEvictAfter.toNanos()
        buckets.values.removeIf { it.lastSeenNanos - cutoff < 0 }
        if (buckets.size > props.maxKeys) clearAll()
    }

    private fun clearAll() {
        val size = buckets.size
        buckets.clear()
        log.warn("rate_limit_keys_cleared size={}", size)
    }

    companion object {
        const val PATH_PATTERN = "/api/**"
        val EXCLUDED_PATTERNS = listOf(AdminTokenInterceptor.PATH_PATTERN, "/api/webhooks/**", "/api/ping")
        const val QUEUE_ME = "/api/showtimes/{showtimeId}/queue/me"
        const val MEMBER_HEADER = "X-Member-Id"

        /** Lower than [AdminTokenInterceptor.ORDER]: this interceptor runs first (ADR-0010 §1). */
        const val ORDER = 0
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("openrun.ratelimit.enabled", havingValue = "true")
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfig(
    private val interceptor: RateLimitInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor)
            .addPathPatterns(RateLimitInterceptor.PATH_PATTERN)
            .excludePathPatterns(RateLimitInterceptor.EXCLUDED_PATTERNS)
            .order(RateLimitInterceptor.ORDER)
    }
}
