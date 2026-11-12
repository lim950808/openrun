package dev.openrun.api

import dev.openrun.booking.hold.RedisSeatHolds
import dev.openrun.payment.FakePg
import dev.openrun.payment.PaymentProperties
import dev.openrun.payment.PgClient
import dev.openrun.payment.SeatHoldHook
import dev.openrun.payment.TossPgClient
import dev.openrun.payment.WebhookSignature
import dev.openrun.support.Clocks
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant

/** ADR-0007 §2·§6: `openrun.payment.pg=fake|toss` picks the [PgClient]; `toss` without a secret key fails at startup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties::class)
class PaymentConfig {
    @Bean
    fun clock(): Clock = Clocks.default

    @Bean
    fun webhookSignature(props: PaymentProperties): WebhookSignature {
        require(props.toss.webhookSecret.isNotBlank()) { "openrun.payment.toss.webhook-secret (OPENRUN_TOSS_WEBHOOK_SECRET) is required" }
        return WebhookSignature(props.toss.webhookSecret)
    }

    @Bean
    @ConditionalOnProperty(name = ["openrun.payment.pg"], havingValue = "fake", matchIfMissing = true)
    fun fakePg(props: PaymentProperties, clock: Clock, signature: WebhookSignature): FakePg =
        FakePg(clock, signature.takeIf { props.fakePg.webhooks }, props.fakePg.webhookUrl)

    @Bean
    @ConditionalOnProperty(name = ["openrun.payment.pg"], havingValue = "toss")
    fun tossPgClient(props: PaymentProperties): PgClient {
        require(props.toss.secretKey.isNotBlank()) { "openrun.payment.pg=toss needs openrun.payment.toss.secret-key (OPENRUN_TOSS_SECRET_KEY)" }
        return TossPgClient(RestClient.builder().requestFactory(TossPgClient.requestFactory()), props.toss.secretKey, props.toss.baseUrl)
    }

    /** Redis side of payment transitions for the `REDIS_LUA` strategy (keys of other strategies do not exist). */
    @Bean
    fun seatHoldHook(redisHolds: RedisSeatHolds): SeatHoldHook = object : SeatHoldHook {
        override fun pin(showtimeId: Long, seatIds: List<Long>, reservationId: Long) {
            redisHolds.pin(showtimeId, seatIds, reservationId, null)
        }

        override fun unpin(showtimeId: Long, seatIds: List<Long>, reservationId: Long, expiresAt: Instant) {
            redisHolds.pin(showtimeId, seatIds, reservationId, expiresAt.toEpochMilli())
        }

        override fun release(showtimeId: Long, seatIds: List<Long>, reservationId: Long) {
            redisHolds.release(showtimeId, seatIds, reservationId)
        }
    }
}
