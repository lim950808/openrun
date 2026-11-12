# ADR-0007 결제: PG 어댑터, Fake PG, 웹훅 인박스, 결제 janitor

- 상태: 제안
- 날짜: 2026-11-12
- 관련: [ADR-0004](0004-state-transitions.md)(전이표·불변식·웹훅 규칙은 그대로 따른다), [ADR-0003](0003-jpa-jdbc-boundary.md) `payment` · `payment_event_inbox`, [요구사항](../requirements.md) M3-1~M3-8, [지표](../metrics.md) M3, 리서치 02

## 맥락

ADR-0004가 "무엇이 어떤 조건에서 어디로 가는가"를 정했다. 이 ADR은 그 전이표를 실행하는 **바깥 경계**를 정한다. 토스 API를 어떻게 부르고 응답을 세 갈래(승인·확정 실패·미확정)로 어떻게 나누는가, 토스 없이 테스트와 주간 오픈런을 어떻게 돌리는가(Fake PG), `eventId`가 없는 `PAYMENT_STATUS_CHANGED` 웹훅을 어떻게 중복 제거하는가, janitor 세 경로(ADR-0004 §2·§3)를 어느 스케줄러가 어떤 SQL로 도는가.

토스 사양(2026-11-12 문서 기준): 승인 `POST /v1/payments/confirm {paymentKey, orderId, amount}`, 조회 `GET /v1/payments/orders/{orderId}`, 취소 `POST /v1/payments/{paymentKey}/cancel {cancelReason}`. 인증은 `Authorization: Basic base64("{secretKey}:")`. `Idempotency-Key`는 POST 전용, 300자, 15일, 동시 중복은 `409 IDEMPOTENT_REQUEST_PROCESSING`, 재전송은 첫 응답 그대로. 결제 인증 후 10분 안에 confirm하지 않으면 `NOT_FOUND_PAYMENT_SESSION`. 웹훅은 10초 안에 200, 실패 시 7회 재전송(1·2·4·16·64·256·1024분), 순서 보장 없음, 서명 헤더 `tosspayments-webhook-signature`(`v1:` 뒤 base64 값들, HMAC-SHA256 over `{payload}:{tosspayments-webhook-transmission-time}`), `PAYMENT_STATUS_CHANGED` 본문에는 `eventId`가 없다.

## 결정

### 1. PG는 인터페이스 하나 뒤에 두고 응답을 세 갈래로만 돌려준다

`domain-payment`의 `PgClient`:

| 호출 | 결과(sealed) | 토스 매핑 |
|---|---|---|
| `confirm(paymentKey, orderId, amount, idempotencyKey)` | `Approved(paymentKey, approvedAt, totalAmount)` · `Declined(code, message)` · `Unknown(cause)` | 200 DONE → Approved. `REJECT_CARD_COMPANY` · `INVALID_REQUEST` · `NOT_FOUND_PAYMENT` · `NOT_FOUND_PAYMENT_SESSION` 등 4xx 확정 실패 → Declined. `ALREADY_PROCESSED_PAYMENT`(이미 승인된 결제를 다시 confirm) · `PROVIDER_ERROR` · 5xx · 타임아웃 · 연결 오류 · `409 IDEMPOTENT_REQUEST_PROCESSING` → Unknown. ALREADY_PROCESSED를 Unknown으로 두는 이유: 돈이 빠졌을 가능성이 있으므로 재조회로 확정해야지 실패로 적으면 안 된다 |
| `inquire(orderId)` | `Found(status, paymentKey, approvedAt, totalAmount)` · `NotFound` · `Unknown(cause)` | `GET /v1/payments/orders/{orderId}`. 404 → NotFound(토스에 결제 자체가 없으니 FAILED로 확정) |
| `cancel(paymentKey, reason, idempotencyKey)` | `Cancelled(canceledAt)` · `AlreadyCancelled` · `Declined(code)` · `Unknown(cause)` | `ALREADY_CANCELED_PAYMENT` → AlreadyCancelled(보상 성공으로 취급) |

`TossPgClient`는 Spring `RestClient`로 구현한다. 연결 3초, 읽기 10초. 타임아웃은 재시도하지 않는다. 재시도는 janitor의 재조회가 맡는다(ADR-0004 §4). 토스 호출은 트랜잭션 밖에서 한다.

### 2. Fake PG는 같은 인터페이스의 또 다른 구현이고, 고장을 요청 단위로 주입한다

`openrun.payment.pg=fake|toss`로 고른다. 테스트·CI·주간 오픈런·벤치는 `fake`, 실연동 영상(M3-1)은 `toss`. `FakePg`는 앱 안에서 돌며 결제 인증 단계도 흉내 낸다.

| 경로 | 역할 |
|---|---|
| `POST /api/fake-pg/payments {orderId, amount, fault?}` | 결제위젯의 "인증" 대체. `paymentKey`를 발급하고 `fault`를 그 결제에 기억한다. `pg=fake`일 때만 열린다 |
| `fault` 값 | `NONE` · `DECLINE`(confirm이 Declined) · `TIMEOUT`(confirm이 읽기 타임아웃, 토스 쪽은 승인됨) · `TIMEOUT_THEN_INQUIRY_DOWN`(위에 더해 재조회가 `Unknown`을 `inquiry_down_seconds` 동안 반환) · `DELAY`(confirm 응답을 `delay_seconds` 뒤에 Approved) |

고장은 확률이 아니라 **요청에 박힌 값**이다. 봇 시나리오 yaml의 `fake_pg.fault_mix`가 어떤 비율로 어떤 fault를 보낼지 정하고, 리포트는 `unknown_count` · `confirm_expire_race_occurred`를 그 주입량과 비교한다(ADR-0002의 복원 검증과 같은 구조). `TIMEOUT`은 M3-4, `DELAY`는 M3-3b, `TIMEOUT_THEN_INQUIRY_DOWN`은 M3-5의 재현 수단이다.

Fake PG는 confirm이 Approved로 끝난 뒤 `fake_pg.webhooks=true`면 자기 자신의 `/api/webhooks/toss`로 서명된 `PAYMENT_STATUS_CHANGED(DONE)` 웹훅을 비동기로 보낸다. 지연·중복·역순 웹훅은 테스트가 직접 서명해서 보낸다(M3-6).

### 3. 테이블

`payment`(JDBC): `id` · `reservation_id` · `attempt` · `order_id`(= `{reservationId}-{attempt}`, unique) · `amount` · `status` · `payment_key` · `approved_at` · `failure_code` · `confirming_since` · `unknown_since` · `created_at` · `updated_at`. 유니크 `(reservation_id, attempt)`. 부분 유니크 인덱스로 **예약당 활성 결제(REQUESTED·CONFIRMING·UNKNOWN·CONFIRMED) 1건**(ADR-0004 불변식 3).

`payment_event_inbox`: `id` · `event_key`(unique) · `event_type` · `order_id` · `payment_key` · `pg_status` · `transmission_id` · `received_at` · `applied_at` · `outcome`(`APPLIED` · `STALE` · `DUPLICATE` · `IGNORED` · `ORPHAN`) · `payload`(jsonb). `event_key`는 본문 `eventId`가 있으면 그 값, 없으면(`PAYMENT_STATUS_CHANGED`) `sha256(eventType|orderId|status|createdAt)`. 재전송은 같은 본문이므로 같은 키가 되고, 같은 상태가 다른 `createdAt`으로 오면 다른 키지만 순위 규칙(ADR-0004 §5)이 `STALE`로 버린다. `transmission_id`는 기록만 한다(토스가 전송마다 새로 발급하므로 중복 제거에 쓰지 않는다).

`payment_compensation`: `payment_id`(PK) · `requested_at` · `attempts` · `last_error` · `done_at`. `CompensationRequested` outbox 이벤트와 같은 트랜잭션에 행을 만든다. janitor가 이 행을 보고 취소를 부른다. 3회 실패면 `compensation_failed_count`로 넘기고 더 시도하지 않는다.

### 4. API

| 메서드 | 경로 | 인증 | 동작 |
|---|---|---|---|
| `POST` | `/api/reservations/{id}/payments` | 소유자(입장 토큰 또는 `X-Member-Id`) | HOLDING → PAYING + payment REQUESTED (한 트랜잭션, 조건부 UPDATE). 응답 `{orderId, amount, attempt, customerKey}`. 활성 결제가 있으면 그 값을 다시 준다(멱등). EXPIRED면 409 `ILLEGAL_TRANSITION` |
| `POST` | `/api/payments/confirm` | 소유자 | `{orderId, paymentKey, amount}`. ADR-0004 §3 REQUESTED → CONFIRMING 가드: 금액 불일치 400 `AMOUNT_MISMATCH`(`amount_mismatch_count`), orderId 없음 404, 예약이 PAYING 아님 409, hold 만료면 409 `HOLD_EXPIRED` + 만료 연쇄(REQUESTED → FAILED, PAYING → EXPIRED) 즉시 실행. 통과하면 CONFIRMING 커밋 → PG 호출 → 결과 커밋. 응답은 결제 뷰 `{status, reservationStatus, paymentKey, approvedAt}` |
| 같은 confirm 두 번(M3-7) | | | 두 번째 요청은 REQUESTED → CONFIRMING 조건부 UPDATE가 0행이라 PG를 부르지 않고 현재 결제 뷰를 돌려준다(`idempotent_replay_count`). 첫 요청이 아직 CONFIRMING이면 `202 {status: CONFIRMING}`, 끝났으면 같은 결과. PG 쪽 멱등키 `{reservationId}:{attempt}`는 앱이 죽은 뒤 janitor가 아니라 사람이 confirm을 다시 보낼 때를 위한 2중 방어다 |
| `POST` | `/api/webhooks/toss` | 서명 | 서명 검증 실패 401(저장 안 함). 통과하면 inbox INSERT(`event_key` 충돌이면 `DUPLICATE`로 기록) 후 즉시 200. 적용은 §5의 워커 |
| `POST` | `/api/reservations/{id}/cancel` | 소유자 | CONFIRMED 예약 전액 취소. PG cancel 성공 → payment CANCELLED + 예약 CANCELLED (한 트랜잭션). 공연 시작 후면 409 |
| `GET` | `/api/reservations/{id}` | 소유자 | 예약·좌석·결제 상태 뷰. 결제 결과 화면과 confirm 폴링이 쓴다 |
| `GET` | `/api/payments/config` | 없음 | `{pg, clientKey}`. 정적 결제 페이지가 토스 위젯을 띄울 때 쓴다 |
| `GET` | `/api/admin/showtimes/{id}/payment-integrity` | 없음 | `hold_to_confirm_rate` · `confirm_expire_race_occurred` · `confirm_expire_race_remaining` · `compensation_failed_count` · `unknown_count` · `unknown_dwell_p99_s` · `webhook_duplicate_count` · `webhook_stale_count` · `amount_mismatch_count` · `idempotent_replay_count` · `cancel_success_rate` · `state_event_mismatch_count` |

실연동 영상용 정적 페이지 `apps/api/src/main/resources/static/pay/index.html`은 토스 결제위젯 v2 SDK로 `requestPayment`를 띄우고 `successUrl`에서 `/api/payments/confirm`을 부른다. 화면 작업은 M4의 Next.js가 하고, 이 페이지는 M3-1 수용 기준(실연동)을 위한 최소 HTML이다.

### 5. 웹훅 적용 워커와 결제 janitor

`PaymentJanitor`(ShedLock, 5초 틱)가 네 경로를 순서대로 돈다. 각 경로는 조건부 UPDATE이고 전이마다 outbox 이벤트를 같은 트랜잭션에 남긴다(ADR-0004 불변식 4).

1. **인박스 적용.** `applied_at is null`인 inbox 행을 받은 순서로 읽어 `order_id`로 payment를 찾고(없으면 `ORPHAN`), 토스 상태 → 내부 상태 매핑과 ADR-0004 §5 두 조건으로 적용·`STALE` 판정. `READY` · `IN_PROGRESS`는 `IGNORED`.
2. **CONFIRMING 30초 초과 → UNKNOWN.** `confirming_since < now() - 30s`. 앱이 PG 응답을 처리하다 죽은 경우다.
3. **UNKNOWN 재조회.** `inquire(orderId)`. `Found(DONE)` → CONFIRMED(예약이 PAYING이면 CONFIRMED, EXPIRED면 `CompensationRequested` + `payment_compensation` 행). `Found(ABORTED|EXPIRED)` · `NotFound` → FAILED(예약 PAYING → HOLDING 또는 EXPIRED). `Unknown` → 그대로 두고 다음 틱. `unknown_since`가 5분을 넘으면 예약만 PAYING → EXPIRED로 풀고 결제는 UNKNOWN에 둔다(ADR-0004 §2).
4. **보상 취소.** `payment_compensation`에서 `done_at is null and attempts < 3`인 행에 `cancel(paymentKey, "hold expired before approval", "comp:{paymentId}")`. `Cancelled` · `AlreadyCancelled` → payment CONFIRMED → CANCELLED, `done_at`. 실패면 `attempts + 1`, `last_error`.

hold 만료 janitor(`HoldExpiryJanitor`)는 M3에서 PAYING 예약도 맡는다. ADR-0004 §2의 순서대로 **먼저** `UPDATE payment SET status = 'FAILED' WHERE reservation_id = ? AND status = 'REQUESTED'`를 실행하고, 1행이면 예약을 EXPIRED로 바꾼다. 0행(CONFIRMING·UNKNOWN·CONFIRMED)이면 건드리지 않는다.

### 6. 비밀과 설정

| 키 | 환경변수 | 비고 |
|---|---|---|
| `openrun.payment.pg` | `OPENRUN_PAYMENT_PG` | 기본 `fake`. CI·주간 오픈런은 `fake` |
| `openrun.payment.toss.secret-key` | `OPENRUN_TOSS_SECRET_KEY` | `test_sk_…`. 레포에 넣지 않는다(CLAUDE.md 규칙 7) |
| `openrun.payment.toss.client-key` | `OPENRUN_TOSS_CLIENT_KEY` | 정적 결제 페이지에 노출되는 공개 키 |
| `openrun.payment.toss.webhook-secret` | `OPENRUN_TOSS_WEBHOOK_SECRET` | 웹훅 서명 키. Fake PG도 같은 키로 서명한다 |
| `openrun.payment.confirming-timeout` | | PT30S |
| `openrun.payment.unknown-release-after` | | PT5M |
| `openrun.payment.janitor-delay-ms` | | 5000 |

`apps/api`는 `spring.config.import=optional:file:.env[.properties]`로 루트 `.env`를 읽는다. `.env.example`에 키 이름만 적는다. 토스 키 없이 `pg=toss`로 띄우면 기동 시 실패한다.

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| Fake PG를 별도 프로세스(WireMock 등)로 | 고장 주입을 요청 단위로 박으려면 결제 인증 단계까지 흉내 내야 하는데, 인터페이스 구현 하나가 가장 짧고 테스트에서 같은 JVM이라 시간 제어가 쉽다. 네트워크 타임아웃의 "진짜" 재현은 `TossPgClient`의 RestClient 타임아웃 단위 테스트가 맡는다 |
| 고장을 확률로 주입 | 주입량을 모르면 `unknown_count`를 비교할 수 없다. 확률은 시나리오 yaml의 혼합 비율로만 쓰고, 개별 요청에는 값으로 박는다 |
| 웹훅 중복 제거를 `transmission_id`로 | 토스가 재전송마다 새 id를 발급하므로 재전송이 중복으로 안 잡힌다 |
| 웹훅 수신 시 동기 적용 | ADR-0004 §5가 기각. 10초 제한과 DB 경합 분리 |
| ALREADY_PROCESSED_PAYMENT를 Declined로 | 이미 승인된 결제를 실패로 적으면 돈은 빠졌는데 좌석이 풀린다. 재조회로 확정 |
| confirm 재시도를 앱이 직접 | 재시도 중 hold가 만료되는 경로가 하나 더 생긴다. UNKNOWN 한 상태로 모아 janitor 재조회 하나로 처리 |
| 정적 결제 페이지 생략, M4까지 대기 | M3-1 수용 기준이 "실연동"이라 토스 위젯을 띄울 화면이 M3 안에 있어야 한다 |

## 결과

- 좋아지는 것: PG 호출 결과가 세 갈래뿐이라 전이표의 행과 1:1로 대응한다. Fake PG의 고장이 요청 단위라 리포트의 모든 결제 이상 지표가 "주입량 대비"로 읽힌다. 토스 키 없이 전체 흐름(주간 오픈런 포함)이 돈다.
- 감수하는 것: 봇은 토스 결제위젯을 거치지 않으므로 주간 오픈런의 결제 지표는 Fake PG 기준이다. 실제 토스는 M3-1 영상과 수동 확인에만 쓴다. 웹훅 적용이 5초 틱이라 `webhook_apply_lag_p99_s` 목표(5초)를 틱이 거의 다 쓴다. 필요하면 틱을 1초로 줄인다.
- 다시 검토할 조건: 멀티 PG(카카오페이)가 들어오면 `PgClient`를 PG별 라우팅 뒤로 옮기고 `payment.pg` 컬럼을 추가한다. 1.5차 부분취소는 `cancel`에 금액 인자를 더한다.

## 증명

- `PaymentTransitionTest`(domain-payment): ADR-0004 §3 전이표의 모든 (from, to) 쌍, 허용은 이벤트·비허용은 0행
- `PaymentApiTest`: M3-2 금액 변조 400·REQUESTED 유지·hold 유지, M3-3 만료 후 confirm 409·연쇄, M3-7 같은 confirm 두 번에 PG 호출 1회·응답 동일, M3-8 취소
- `FakePgRaceTest`: M3-3b `DELAY`로 응답이 만료 뒤 도착 → CONFIRMED·오버셀 0, M3-4 `TIMEOUT` → UNKNOWN → janitor 재조회 → CONFIRMED, M3-5 `TIMEOUT_THEN_INQUIRY_DOWN` → 5분 뒤 EXPIRED → 승인 확인 → 보상 취소 → `confirm_expire_race_remaining` 0 (시간은 Clock 주입으로 당긴다)
- `WebhookInboxTest`: M3-6 서명 실패 401, 중복 `DUPLICATE`, 역순 `STALE`, 지연 적용, 과거 attempt의 orderId는 현재 attempt에 영향 없음, 수신 응답 p99 1초 이하
- `TossPgClientTest`: MockRestServiceServer로 200·4xx·5xx·타임아웃 → Approved·Declined·Unknown 매핑, 인증 헤더·멱등키 헤더
- `load/k6/weekly-openrun.js`에 결제 단계 추가, `GET /payment-integrity`의 `unknown_count`가 시나리오 `fake_pg.fault_mix` 주입량과 일치
