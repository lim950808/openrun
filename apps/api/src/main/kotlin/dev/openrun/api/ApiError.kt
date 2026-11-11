package dev.openrun.api

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Error body for 4xx responses. */
data class ApiError(val code: String)

/**
 * Builds 4xx responses and counts them in `openrun.api.errors{code}` (source for `illegal_transition_count`
 * via `code=ILLEGAL_TRANSITION`). Logged at DEBUG only: at benchmark rates a WARN per 409 distorts latency.
 * Unhandled 5xx keep Spring's default ERROR logging.
 */
@Component
class ApiErrors(
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun respond(status: HttpStatus, code: String): ResponseEntity<Any> {
        Counter.builder("openrun.api.errors").tag("code", code).register(registry).increment()
        log.debug("api_error status={} code={}", status.value(), code)
        return ResponseEntity.status(status).body(ApiError(code))
    }
}

/** Maps request-shape failures raised by Spring MVC before a controller runs. Domain outcomes are mapped in controllers. */
@RestControllerAdvice
class ApiErrorAdvice(
    private val errors: ApiErrors,
) {
    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException) = errors.respond(HttpStatus.BAD_REQUEST, "MISSING_HEADER")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(e: MethodArgumentTypeMismatchException) = errors.respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) = errors.respond(HttpStatus.BAD_REQUEST, "INVALID_BODY")
}
