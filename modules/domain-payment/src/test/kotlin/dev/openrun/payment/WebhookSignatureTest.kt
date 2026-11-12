package dev.openrun.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebhookSignatureTest {
    private val signer = WebhookSignature("secret")

    @Test
    fun `M3_6 같은 키로 서명한 값은 검증되고 본문이나 시각이 바뀌면 실패`() {
        val header = signer.sign("{}", "1700000000")

        assertThat(header).startsWith("v1:")
        assertThat(signer.verify("{}", "1700000000", header)).isTrue()
        assertThat(signer.verify("{ }", "1700000000", header)).isFalse()
        assertThat(signer.verify("{}", "1700000001", header)).isFalse()
        assertThat(signer.verify("{}", null, header)).isFalse()
        assertThat(WebhookSignature("other").verify("{}", "1700000000", header)).isFalse()
    }

    @Test
    fun `M3_6 쉼표로 여러 값이 오면 하나만 맞아도 통과`() {
        val header = "v1:bm9wZQ==, " + signer.sign("{}", "1")
        assertThat(signer.verify("{}", "1", header)).isTrue()
        assertThat(signer.verify("{}", "1", "v1:not-base64!!")).isFalse()
    }
}
