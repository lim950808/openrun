package dev.openrun.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.util.UUID

class SupportTest {
    @Test
    fun `newId returns distinct UUID strings`() {
        val a = Ids.newId()
        val b = Ids.newId()
        assertThat(a).isNotEqualTo(b)
        assertThat(UUID.fromString(a).toString()).isEqualTo(a)
    }

    @Test
    fun `default clock is UTC`() {
        assertThat(Clocks.default.zone).isEqualTo(ZoneOffset.UTC)
    }
}
