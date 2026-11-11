package dev.openrun.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["dev.openrun"])
@EntityScan(basePackages = ["dev.openrun.catalog"])
@EnableJpaRepositories(basePackages = ["dev.openrun.catalog"])
class OpenrunApplication

fun main(args: Array<String>) {
    runApplication<OpenrunApplication>(*args)
}
