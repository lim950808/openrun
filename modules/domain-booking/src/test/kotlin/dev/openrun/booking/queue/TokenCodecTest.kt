package dev.openrun.booking.queue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class TokenCodecTest {
    private val secret = "test-secret-at-least-thirty-two-bytes-long"
    private val now = Instant.parse("2026-11-28T11:00:00Z")
    private val codec = codecAt(now)

    private fun codecAt(at: Instant, key: String = secret) =
        TokenCodec(key, Duration.ofHours(3), Duration.ofMinutes(10), Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun `M2_2 입장 토큰을 발급하고 Redis 없이 검증한다`() {
        val minted = codec.mintAccess(memberId = 7, showtimeId = 42)

        assertThat(minted.expiresAt).isEqualTo(now.plus(Duration.ofMinutes(10)))
        assertThat(codec.verify(minted.token, TokenType.ACCESS, 42)).isEqualTo(TokenCheck.Valid(7, 42, minted.expiresAt))
        assertThat(codec.verify(minted.token, TokenType.ACCESS, null)).isEqualTo(TokenCheck.Valid(7, 42, minted.expiresAt))
    }

    @Test
    fun `M2_1 대기 토큰은 3시간 유효하다`() {
        val minted = codec.mintVisitor(memberId = 7, showtimeId = 42)

        assertThat(minted.expiresAt).isEqualTo(now.plus(Duration.ofHours(3)))
        assertThat(codec.verify(minted.token, TokenType.VISITOR, 42)).isInstanceOf(TokenCheck.Valid::class.java)
    }

    @Test
    fun `M2_4 만료된 토큰은 Expired`() {
        val minted = codecAt(now.minus(Duration.ofMinutes(11))).mintAccess(7, 42)

        assertThat(codec.verify(minted.token, TokenType.ACCESS, 42)).isEqualTo(TokenCheck.Expired)
    }

    @Test
    fun `서명 키가 다르면 Invalid`() {
        val minted = codecAt(now, key = "another-secret-also-thirty-two-bytes-long").mintAccess(7, 42)

        assertThat(codec.verify(minted.token, TokenType.ACCESS, 42)).isEqualTo(TokenCheck.Invalid)
    }

    @Test
    fun `대기 토큰으로 입장 토큰 자리를 쓰면 Invalid`() {
        val visitor = codec.mintVisitor(7, 42)

        assertThat(codec.verify(visitor.token, TokenType.ACCESS, 42)).isEqualTo(TokenCheck.Invalid)
    }

    @Test
    fun `다른 회차 토큰은 Invalid`() {
        val minted = codec.mintAccess(7, 42)

        assertThat(codec.verify(minted.token, TokenType.ACCESS, 43)).isEqualTo(TokenCheck.Invalid)
    }

    @Test
    fun `깨진 문자열은 Invalid`() {
        assertThat(codec.verify("not-a-jwt", TokenType.ACCESS, 42)).isEqualTo(TokenCheck.Invalid)
        // Member 8's signature on member 7's claims.
        val forged = codec.mintAccess(7, 42).token.substringBeforeLast('.') + "." + codec.mintAccess(8, 42).token.substringAfterLast('.')
        assertThat(codec.verify(forged, TokenType.ACCESS, 42)).isEqualTo(TokenCheck.Invalid)
    }

    @Test
    fun `32바이트 미만 비밀값은 SHA-256으로 늘려 쓴다`() {
        val short = codecAt(now, key = "short")
        val minted = short.mintAccess(7, 42)

        assertThat(short.verify(minted.token, TokenType.ACCESS, 42)).isInstanceOf(TokenCheck.Valid::class.java)
        assertThat(codec.verify(minted.token, TokenType.ACCESS, 42)).isEqualTo(TokenCheck.Invalid)
    }
}
