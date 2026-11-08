# 리서치 02 — 결제·커머스: 친구 레포(payment-consistency-lab) 위에 무엇을 쌓을 것인가

조사일: 2026-11-08~09. GitHub 메타데이터는 API 기준. **(미검증)** 표기는 원문 확인 실패.

## (A) 친구 레포 요약 — 반복하지 말 것 / 재사용할 것

jaehafe/payment-consistency-lab — Kotlin(Spring Boot 3.5, JdbcTemplate, Gradle 멀티모듈) + Go 병렬 구현, Testcontainers(PostgreSQL/Redpanda), Debezium, Prometheus/Grafana. 10단계: 01 Dual Write → 02 멱등키 → 03 Outbox polling(`SKIP LOCKED`) → 04 Outbox CDC → 05 멱등 컨슈머 → 06 잔액 동시성 → 07 결제 상태머신(`UNKNOWN`, janitor) → 08 복식부기 원장 → 09 대사 → 10 관측성.

README가 밝힌 한계: 04 E2E 자동테스트 없음, 07 `PENDING` 크래시 건 janitor 미대상, 05 DLQ 없음, 08 PG 수수료 계정 없음, 10 알림 미검증.

**반복하지 말 것**: 계좌 잔액 lost update 실험(06), 결제 단독 상태머신(07), Outbox polling vs CDC 비교(03/04), Stripe식 멱등키 서버 구현(02), PG 정산파일 1:1 대사(09).

**재사용할 것**: 03 outbox relay + 05 `processed_messages` → 우리는 inbound webhook inbox로 확장. 08 원장 스키마 → 취소수수료·부분환불 계정 추가. 10 request_id 로그 → OpenTelemetry trace로 승격. "주장 → 테스트로 증명" 서술 방식.

**차별화 축**: (1) 실제 PG(Toss) 연동 — 10분 승인창, 웹훅 서명/재시도, 부분취소 (2) 좌석 hold TTL ↔ 결제 승인 레이스 (3) 취소 수수료·부분환불·대기자 승계 (4) 매크로/봇 방어·입장 제어 (5) 멀티 PG 라우팅/장애 격리.

## (B-1) 한국 PG 연동

| 프로젝트/문서 | 포인트 | 링크 |
|---|---|---|
| tosspayments/tosspayments-sample (83★, 2026-04) | 결제위젯 → `successUrl` → 서버 `POST /v1/payments/confirm` 골격. `spring-javascript` 디렉터리 | https://github.com/tosspayments/tosspayments-sample |
| tosspayments/browser-sdk (127★, 2026-10) | 결제위젯 v2 클라이언트 SDK | https://github.com/tosspayments/browser-sdk |
| Toss 멱등키 문서 | 헤더 `Idempotency-Key`, 최대 300자, 15일 유효, 모든 POST 적용, `409 IDEMPOTENT_REQUEST_PROCESSING` | https://docs.tosspayments.com/reference/using-api/authorization |
| Toss 승인/취소 레퍼런스 | **결제 요청 후 10분 내 confirm** 아니면 `NOT_FOUND_PAYMENT_SESSION`. 취소 `cancelAmount`로 부분취소, `cancels[].transactionKey` 누적, `isPartialCancelable`, 가상계좌 입금 전 부분취소 불가 | https://docs.tosspayments.com/reference |
| Toss 웹훅 이벤트/검증 | `PAYMENT_STATUS_CHANGED`, `DEPOSIT_CALLBACK`, `CANCEL_STATUS_CHANGED`. HMAC-SHA256 서명 헤더. 중복제거는 body `eventId`(단 `PAYMENT_STATUS_CHANGED`엔 없음). **이벤트 순서 보장 안 됨** | https://docs.tosspayments.com/reference/using-api/webhook-events |
| Toss 웹훅 가이드 v2 | 10초 내 200, 실패 시 최대 7회 재전송(1,4,16,64,256,1024,4096분), 콘솔 수동 재전송 | https://docs.tosspayments.com/guides/v2/webhook |
| Toss 결제위젯 연동 가이드 | `requestPayment` 전 서버에 orderId/amount 저장 → 리다이렉트 `amount` 비교(변조 방어) → confirm. 계약 전엔 문서 테스트 키 사용. 가상계좌는 문서 테스트 키로 불가 | https://docs.tosspayments.com/guides/v2/payment-widget/integration |
| Toss API 키 | 이메일 가입만으로 테스트 키, 테스트 결제내역·웹훅 사용 가능 | https://docs.tosspayments.com/reference/using-api/api-keys |
| portone-io/server-sdk (18★, 2026-10) | JVM SDK `io.portone:server-sdk`. 웹훅 검증 API명 (미검증) | https://github.com/portone-io/server-sdk |
| PortOne V2 웹훅 | Standard Webhooks 규격, 재시도 5회, 권고: 서명검증 → API 재조회 → 금액 비교 | https://developers.portone.io/opi/ko/integration/webhook/readme-v2 |
| 카카오페이 개발자센터 | ready → approval_url+pg_token → approve. 테스트 CID `TC0ONETIME`. 테스트 결제 매일 자정 일괄 취소. 무계약 테스트 가능 여부 (미검증) | https://developers.kakaopay.com/docs/payment/online/single-payment |

결론: "진짜" 연동은 Toss 결제위젯 + confirm + 웹훅이 가장 안전. 카카오페이는 2번째 PG로 멀티 PG 라우팅 시연용. PortOne은 PG 추상화 계층 레퍼런스.

## (B-2) 커머스 엔진의 "재고 예약 / 결제 세션" 모델

| 프로젝트 | 언어 | Stars | 최근활동 | 포인트 | 링크 |
|---|---|---|---|---|---|
| medusajs/medusa | TS | 37.1k | 2026-11 | `InventoryLevel{stocked, reserved, incoming}` + `ReservationItem`. `completeCartWorkflow`: lock → 검증 → 주문 생성 → 재고 예약 → **결제 승인은 마지막** → 보상(`compensatePaymentIfNeededStep`). PaymentSession 상태 | https://docs.medusajs.com/resources/references/medusa-workflows/completeCartWorkflow |
| saleor/saleor | Python | 23.5k | 2026-11 | 체크아웃 재고 예약(인증/비인증별 시간, `stockReservationExpires`). TransactionItem: `pspReference+type+amount` 동일하면 `alreadyProcessed`, 금액 다르면 `INCORRECT_DETAILS` — PG 이벤트 멱등 수용의 교과서 | https://docs.saleor.io/developer/stock/stock-reservation · https://docs.saleor.io/developer/payments/transactions |
| vendurehq/vendure | TS | 8.6k | 2026-11 | `saleable = stockOnHand − allocated − threshold`; 할당은 `PaymentAuthorized/Settled` 전이 시점(전략 교체 가능). 2단계 authorize→settle | https://docs.vendure.io/guides/core-concepts/stock-control/ |
| Sylius/Sylius | PHP | 8.6k | 2026-11 | YAML 선언형 결제 상태머신 예 | https://old-docs.sylius.com/en/1.12/book/orders/payments.html |
| shopizer / Broadleaf / OFBiz | Java | 4.0k/1.9k/1.1k | 2025-03/2026-11/2026-11 | shopizer 정체, Broadleaf는 Fair Use 라이선스(OSS 아님), OFBiz는 과함 | — |
| commercetools 문서 (비OSS) | — | — | — | `InventoryMode ReserveOnCart` + `reservationExpirationInMinutes`(카트 단위 연장) → hold TTL 연장 정책 벤치마크 | https://docs.commercetools.com/tutorials/reserve-stock-on-cart |
| Stripe Checkout limited inventory (비OSS) | — | — | — | 티켓 판매 예시 명시: `expires_at` 30분~24시간, 만료 시 `checkout.session.expired` 웹훅으로 재고 복구. 수동캡처 홀드 7일 | https://docs.stripe.com/payments/checkout/managing-limited-inventory |
| Adyen 문서 (비OSS) | — | — | — | `Idempotency-Key` 64자, `/cancelOrRefund`, 부분환불 합 ≤ 캡처액 | https://docs.adyen.com/online-payments/refund |

## (B-3) 오케스트레이션 / 상태머신 / Kotlin

| 프로젝트 | Stars | 최근활동 | 포인트 | 링크 |
|---|---|---|---|---|
| temporalio/samples-java `bookingsaga` | 264 | 2026-11 | `saga.addCompensation(cancelCar, requestId)`를 `reserveCar` 호출 **전에** 등록 → 타임아웃으로 결과 불명이어도 보상 가능. requestId = PG 멱등키 | https://github.com/temporalio/samples-java/tree/main/core/src/main/java/io/temporal/samples/bookingsaga |
| spring-statemachine | 1.7k | **2026-07 아카이브** | 채택 금지. Kotlin sealed class + 전이 테이블로 직접 구현 | https://github.com/spring-projects/spring-statemachine |
| AxonFramework (+kotlin ext) | 3.6k | 2026-11 | CQRS/ES + Saga. 포폴에 통째로는 무거움 | https://github.com/AxonFramework/AxonFramework |
| camunda (Zeebe) | 4.4k | 2026-11 | 코어 Camunda License, 클라이언트만 Apache-2.0 | https://github.com/camunda/camunda |
| arrow-kt/arrow `arrow-resilience` | 6.6k | 2026-11 | `saga { }` DSL로 인프라 없이 Kotlin 보상 트랜잭션, `Either`로 Success/Failure/Unknown | https://arrow-kt.io/learn/resilience/saga/ |
| resilience4j (+kotlin) | 10.8k | 2026-10 | PG 호출 TimeLimiter + Retry(멱등키) + CircuitBreaker + Bulkhead | https://github.com/resilience4j/resilience4j |
| Spring 코루틴 문서 | — | — | suspend + `TransactionalOperator`, 블로킹 JDBC 혼용 주의 | https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html |

## (B-4) 어뷰징/봇 방어

| 항목 | 포인트 | 링크 |
|---|---|---|
| fingerprintjs (28.7k, MIT) | 디바이스당 동시 hold 수 제한. 정확도 제한적 | https://github.com/fingerprintjs/fingerprintjs |
| bucket4j (2.8k) | 사용자/IP/디바이스별 hold·결제 시도 레이트리밋 | https://github.com/bucket4j/bucket4j |
| Cloudflare Turnstile / ALTCHA(MIT, self-host) | hold 생성 엔드포인트에 한 가지만 적용 | https://www.cloudflare.com/lp/turnstile/ |
| 국내 기사 | 예스24×STCLab 매크로 4,300만 건 차단, 티켓 트래픽 86.5% 자동화(임페르바 인용) | https://sports.khan.co.kr/article/202505290458003 |

## (B-5) 관측성

OpenTelemetry Java agent(2.7k) 무코드 계측 → hold→confirm→webhook 하나의 trace, `orderId` baggage. micrometer-tracing 브리지 대안. Grafana Tempo/Loki/Pyroscope(AGPL). 우아한형제들도 Loki+Grafana로 승인율 하락 알림.

## (B-6) 국내 유사 포폴 — 포화 영역 확인
- jipang9/core-api(Kotlin, 2026-05): 분산락 비교, outbox, 멱등 컨슈머, k6 — 친구 레포와 같은 축. **이 수준에 머물면 차별화 실패.**
- 항해플러스 콘서트 과제류(좌석 임시배정 5분 + 대기열 토큰) 포화 → 결제 승인창 정합, 취소 정책, 대기자 승계로 올려야 함.

## (C) hold→pay→confirm→refund에서 친구 레포에 없는 설계 포인트
1. **Hold TTL과 PG 승인창 정합**: Toss 10분 confirm 강제. hold TTL ≥ PG 창 + 버퍼, confirm 직전 hold 재검증, 승인 후 hold 소실 시 자동 취소.
2. 국내 PG는 auth/capture 분리가 일반적이지 않음 → Stripe `requires_capture` 역할을 **내부 hold 레이어**가 대신. 예약 SM × 결제 SM 두 애그리거트 분리.
3. 금액 변조 방어: 서버 orderId/amount 저장 → 리다이렉트 amount 비교. hold에 가격 스냅샷.
4. 리다이렉트 confirm vs 웹훅 레이스: 상태 전이 단조(monotonic), 역행 이벤트 폐기. Saleor식 중복 판정.
5. Inbound 웹훅 inbox: 서명 검증 → eventId(없으면 합성키) → 저장 → 10초 내 200 → 비동기 처리. 7회/3일19시간 멱등.
6. PG 멱등키 = `reservationId:attempt` 결정적 키. 409 → 백오프. Temporal식 보상 선등록.
7. 취소 수수료 정책 엔진 + 부분취소: 소비자분쟁해결기준(공연) 참고(현행 수치 미검증) → 정책 테이블 → `cancelAmount` → 원장 "취소수수료 수익" 계정.
8. 대기자 승계: 좌석 해제 → outbox → 대기자에게 짧은 TTL hold → hold 체인 정합성.
9. Pay-later = 가상계좌 `DEPOSIT_CALLBACK`, 입금기한까지 hold 연장.
10. 버려진 체크아웃 janitor: (a) hold 만료 미결제 (b) confirm UNKNOWN + hold 임박 (c) 승인 완료·hold 소실 → 자동 취소.
11. 핫 행 경합: "좌석 1만 개에 수만 요청" — 좌석 행 조건부 UPDATE vs 구역 카운터 + Redis Lua vs 입장 제어 3단 비교.
12. 멀티 PG 라우팅·장애 격리(우아한형제들 사례): Toss + 카카오페이.
13. 3-way 대사: 좌석 재고 ↔ 예약 ↔ 결제(PG 조회).
14. 어뷰징 레이어: Turnstile/ALTCHA + Bucket4j + 1인당 동시 hold 상한.
15. 비즈니스 SLI: hold→confirm 전환율, 만료율, 승인율, 웹훅 지연, PG별 p99.
16. 결과형 타입: 카카오페이식 `ActResult(Success/Failure/Unknown)` + 보상 루프 상한.

## (D) 가장 가치 높은 5개
1. Toss 공식 문서 + tosspayments-sample — 가입만으로 테스트 키·웹훅·멱등키·부분취소 동작. 실제 제약이 설계 문제를 만들어 줌.
2. Stripe 문서(limited inventory, PaymentIntent, 수동캡처) — auth/capture를 hold 레이어로 번역하는 근거.
3. Medusa `completeCartWorkflow` + Saleor 재고예약/TransactionItem — 결제 승인은 맨 마지막, 중복 판정 규칙.
4. Temporal `bookingsaga` + Arrow Saga — 보상 선등록 원칙. Spring Statemachine 아카이브 후 자체 SM + 코드형 Saga.
5. 국내 사례 3편(우아한형제들 15236, 카카오페이 msa-transaction, toss.tech payments-legacy-5).

## (E) 참고 글
1. https://docs.tosspayments.com/blog/what-is-idempotency
2. https://www.tosspayments.com/blog/articles/54253
3. https://toss.tech/series/payments-legacy (원장 payments-legacy-5, 정산 -6, 카나리 -2)
4. https://techblog.woowahan.com/15236/ (결제 담당자가 장애에 대응하는 방법, 2023-12)
5. https://tech.kakaopay.com/post/msa-transaction/ (ActResult Success/Failure/Unknown)
6. https://helloworld.kurly.com/blog/2026-outbox-pattern-and-retry-topic
7. https://docs.stripe.com/payments/checkout/managing-limited-inventory
8. https://www.seoul.co.kr/news/2017/03/04/20170304020007 (공연 취소수수료 기준), https://sports.khan.co.kr/article/202505290458003 (매크로 차단)

**미검증**: PortOne JVM 웹훅 검증 API명, 카카오페이 무계약 테스트, Toss 수동 캡처 지원, commercetools TransactionType 목록, Turnstile 무료 한도, 취소수수료 현행 수치.
