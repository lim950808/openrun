package dev.openrun.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** Operator API protection (ADR-0009 §3). */
@ConfigurationProperties("openrun.admin")
data class AdminProperties(
    /** Value callers of the admin paths (`/api/admin/` and below) must send as `X-Admin-Token`. Blank (local, CI, runners) = no check. */
    val token: String = "",
)

/**
 * Guard for the admin paths, [PATH_PATTERN] (ADR-0009 §3). When `openrun.admin.token` is set, the request must carry an equal
 * `X-Admin-Token` header: missing → 401 `ADMIN_TOKEN_REQUIRED`, different → 401 `ADMIN_TOKEN_INVALID`. The comparison
 * is constant time. Rejections go through [ApiErrors.respond], so they are counted in `openrun.api.errors{code}` and
 * carry the same `{"code": …}` body as every other 4xx. A blank token keeps the endpoints open, as before this ADR.
 */
@Component
class AdminTokenInterceptor(
    private val props: AdminProperties,
    private val errors: ApiErrors,
    private val json: ObjectMapper,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val expected = props.token
        if (expected.isBlank()) return true

        val given = request.getHeader(HEADER)
        val code = when {
            given.isNullOrBlank() -> "ADMIN_TOKEN_REQUIRED"
            !MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8)) -> "ADMIN_TOKEN_INVALID"
            else -> return true
        }

        val rejection = errors.respond(HttpStatus.UNAUTHORIZED, code)
        response.status = rejection.statusCode.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(json.writeValueAsString(rejection.body))
        return false
    }

    companion object {
        const val HEADER = "X-Admin-Token"
        const val PATH_PATTERN = "/api/admin/**"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties::class)
class AdminTokenConfig(
    private val interceptor: AdminTokenInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns(AdminTokenInterceptor.PATH_PATTERN)
    }
}
