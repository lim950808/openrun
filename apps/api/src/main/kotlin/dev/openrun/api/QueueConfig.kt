package dev.openrun.api

import dev.openrun.booking.queue.QueueProperties
import dev.openrun.booking.queue.TokenCodec
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueueProperties::class)
class QueueConfig {
    @Bean
    fun tokenCodec(props: QueueProperties): TokenCodec =
        TokenCodec(props.tokenSecret, props.visitorTokenTtl, props.accessTokenTtl)
}
