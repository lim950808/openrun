package dev.openrun.api

import dev.openrun.payment.FakeFault
import dev.openrun.payment.FakePayment
import dev.openrun.payment.FakePg
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class FakeAuthorizeRequest(
    val orderId: String = "",
    val amount: Int = 0,
    val fault: FakeFault = FakeFault.NONE,
    /** DELAY only. */
    val delaySeconds: Long = 2,
    /** TIMEOUT_THEN_INQUIRY_DOWN only; default past the 5-minute UNKNOWN release window. */
    val inquiryDownSeconds: Long = 360,
)

/** Stand-in for the Toss widget's authentication step (ADR-0007 §2). Exists only with `openrun.payment.pg=fake`. */
@RestController
@ConditionalOnProperty(name = ["openrun.payment.pg"], havingValue = "fake", matchIfMissing = true)
class FakePgController(
    private val fakePg: FakePg,
) {
    @PostMapping("/api/fake-pg/payments")
    fun authorize(@RequestBody body: FakeAuthorizeRequest): FakePayment =
        fakePg.authorize(body.orderId, body.amount, body.fault, body.delaySeconds, body.inquiryDownSeconds)
}
