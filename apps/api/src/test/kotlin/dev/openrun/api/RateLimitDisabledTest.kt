package dev.openrun.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester

/** ADR-0010 §2 default: off, so the runners and CI see no change. Same context as the other default tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class RateLimitDisabledTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val context: ApplicationContext,
) {
    @Test
    fun `P1_1 꺼진 기본 설정에서는 인터셉터 빈이 없다`() {
        assertThat(context.getBeanNamesForType(RateLimitInterceptor::class.java)).isEmpty()
        repeat(10) { assertThat(mvc.get().uri("/api/showtimes").exchange()).hasStatusOk() }
    }
}
