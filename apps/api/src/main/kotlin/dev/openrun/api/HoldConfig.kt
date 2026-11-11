package dev.openrun.api

import dev.openrun.booking.hold.HoldProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HoldProperties::class)
class HoldConfig
