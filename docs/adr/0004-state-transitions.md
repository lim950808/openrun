# ADR-0004 좌석·예약·결제 상태 전이 규칙

- 상태: 승인
- 날짜: 2026-11-10
- 관련: [블루프린트 5-1~5-3](../blueprint.md), [요구사항](../requirements.md), [용어집](../glossary.md), [지표 정의](../metrics.md), 리서치 02

## 맥락

블루프린트의 상태 다이어그램은 어떤 전이가 있는지는 보여주지만 가드 조건과 부수효과는 보여주지 않는다. M1~M3의 테스트 케이스는 "허용된 전이가 되는가"보다 "허용되지 않은 전이가 막히는가"와 "전이마다 부수효과가 빠짐없이 일어나는가"에서 나온다. 또 친구 레포와 달리 예약과 결제를 두 애그리거트로 나누므로 둘 사이의 불변식을 글로 고정해야 한다.

가장 위험한 구간은 "confirm을 호출하는 동안 hold가 만료되는" 순간이다. 이 구간의 규칙이 없으면 돈은 빠졌는데 예약은 영원히 PAYING에 남거나, 좌석이 두 번 팔린다. 이 ADR의 핵심은 그 구간을 닫는 것이다.

## 결정

### 1. 좌석 상태는 파생 상태다

좌석 자체는 상태 컬럼을 갖지 않는다. 회차 좌석의 상태는 예약·결제 상태에서 계산한다.

| 조건 | SeatStatus |
|---|---|
| 예약 HOLDING이고 `expires_at > now` | `HELD` |
| 예약 PAYING (만료 시각과 무관) | `HELD` |
| 예약 CONFIRMED | `SOLD` |
| 그 외 (예약 없음, EXPIRED, CANCELLED, HOLDING인데 만료) | `AVAILABLE` |

PAYING은 만료 시각을 보지 않는다. 결제가 진행 중인 좌석은 janitor가 예약을 EXPIRED로 바꾸기 전까지 다른 회원이 잡을 수 없다. 이것이 confirm 중 만료 문제를 닫는 첫 번째 규칙이다.

파생 상태로 정의하는 이유: M1은 락 전략 5종을 비교하므로 "진실의 원천"이 전략마다 다르다(DB 행 vs Redis 키). 단 모든 전략은 hold 성공 시 `seat_hold` 행을 남긴다. Redis Lua 전략도 Redis 키는 경합 판정에만 쓰고 결과는 DB에 적는다. 그래야 검증 쿼리가 전략과 무관하게 같다. 기본 전략은 M1 결과로 ADR-0005에서 정한다.

### 2. 예약 상태 전이표

| from | to | 트리거 | 가드 | 부수효과 |
|---|---|---|---|---|
| 없음 | HOLDING | hold 요청 | 입장 토큰 유효 · 요청 좌석 전부 AVAILABLE · 회원에게 활성 예약(HOLDING·PAYING) 없음 · 좌석 수 1~2 | `seat_hold` 생성(`expires_at` = now + 12m) · 가격 스냅샷 · outbox `ReservationHeld` |
| HOLDING | PAYING | 결제 시작 요청 | 요청자 = 소유자 · `expires_at > now` | payment REQUESTED 생성(attempt, orderId, amount) · outbox `ReservationPaymentStarted` |
| HOLDING | EXPIRED | janitor 또는 다음 접근 시 lazy 확인 | `expires_at <= now` | outbox `ReservationExpired` |
| HOLDING | CANCELLED | 사용자 해제 | 요청자 = 소유자 | outbox `ReservationCancelled` |
| PAYING | CONFIRMED | payment CONFIRMED 전이 (같은 트랜잭션) | 예약이 PAYING | outbox `ReservationConfirmed` |
| PAYING | HOLDING | payment FAILED 전이 (같은 트랜잭션) | `expires_at > now` | outbox `ReservationPaymentFailed` (같은 hold로 재시도 허용) |
| PAYING | EXPIRED | janitor | `expires_at <= now` 이고 (payment REQUESTED → FAILED 조건부 UPDATE가 1행 성공) 또는 (payment UNKNOWN이 5분 이상 지속) | outbox `ReservationExpired`. 순서가 중요하다: janitor는 **먼저** payment를 `UPDATE ... SET FAILED WHERE status = 'REQUESTED'`로 바꾸고, 그것이 1행이면 예약을 만료시킨다. 0행이면(그 사이 confirm이 CONFIRMING으로 옮김) 예약을 건드리지 않는다. 이 순서가 confirm 직후 janitor가 끼어드는 경계 레이스를 막는다 |
| PAYING | EXPIRED | payment FAILED 전이 (같은 트랜잭션) | `expires_at <= now` | outbox `ReservationExpired` |
| CONFIRMED | CANCELLED | 취소 요청 | 공연 시작 전 · payment CANCELLED 성공 (같은 트랜잭션) | outbox `ReservationCancelled` |
| EXPIRED · CANCELLED | 없음 | 종료 상태 | | |

좌석 해제는 별도 부수효과가 아니다. 예약이 EXPIRED·CANCELLED가 되면 1번 규칙에 따라 좌석이 AVAILABLE로 파생된다. Redis Lua 전략에서는 Redis 키 삭제가 같은 유스케이스 안에서 따라온다.

janitor는 payment가 CONFIRMING·UNKNOWN(5분 미만)·CONFIRMED인 PAYING 예약을 만료시키지 않는다. 이것이 confirm 중 만료 문제를 닫는 두 번째 규칙이다. 토스 조회까지 5분 넘게 실패하는 경우에만 좌석을 풀고, 그 뒤 승인이 확인되면 보상한다(불변식 2).

허용되지 않은 전이의 예: EXPIRED → PAYING(만료 후 결제 시작), CONFIRMED → HOLDING, HOLDING → CONFIRMED(결제 없이 확정). 전부 409로 거부하고 `illegal_transition_count`에 센다.

### 3. 결제 상태 전이표

| from | to | 트리거 | 가드 | 부수효과 |
|---|---|---|---|---|
| 없음 | REQUESTED | 예약 PAYING 전이 | 예약에 활성 결제(REQUESTED·CONFIRMING·UNKNOWN·CONFIRMED) 없음 | `attempt` = 이전 최대 + 1 · `orderId = {reservationId}-{attempt}` · outbox `PaymentRequested` |
| REQUESTED | CONFIRMING | `/payments/confirm` 수신 | 서버 amount = 요청 amount · orderId 일치 · 예약 PAYING · `expires_at > now` | 토스 confirm 호출, `Idempotency-Key = {reservationId}:{attempt}` · outbox `PaymentConfirming` |
| REQUESTED | FAILED | failUrl 콜백 · 예약 만료 연쇄 | | 예약이 PAYING이고 미만료면 HOLDING으로 · outbox `PaymentFailed` |
| CONFIRMING | CONFIRMED | 토스 200 (DONE) 또는 DONE 웹훅 | | paymentKey · approvedAt 저장 · 예약 PAYING → CONFIRMED · outbox `PaymentConfirmed` |
| CONFIRMING | FAILED | 토스 4xx 확정 실패 또는 ABORTED·EXPIRED 웹훅 | | 실패 코드 저장 · 예약 PAYING → HOLDING(미만료) 또는 EXPIRED(만료) · outbox `PaymentFailed` |
| CONFIRMING | UNKNOWN | 타임아웃 · 5xx · 네트워크 오류 · janitor(CONFIRMING 30초 이상 체류) | | janitor 재조회 예약 · outbox `PaymentUnknown` |
| UNKNOWN | CONFIRMED | 재조회 DONE · DONE 웹훅 · 지연 도착한 토스 200 | | 예약이 PAYING이면 CONFIRMED로 · outbox `PaymentConfirmed`. 예약이 EXPIRED면 같은 트랜잭션에 outbox `CompensationRequested`를 적재하고 janitor가 60초 안에 토스 취소 호출 → CANCELLED |
| UNKNOWN | FAILED | 재조회 ABORTED·EXPIRED 또는 해당 웹훅 | | 예약 PAYING → HOLDING(미만료) 또는 EXPIRED(만료) · outbox `PaymentFailed` |
| CONFIRMED | CANCELLED | 취소 API 200 또는 보상 취소 200 | | 사용자 취소면 예약 CONFIRMED → CANCELLED, 보상이면 예약은 EXPIRED 유지 · outbox `PaymentCancelled` |
| FAILED · CANCELLED | 없음 | 종료 상태 | | |

confirm 요청 거부(amount 불일치, orderId 불일치, 예약이 PAYING 아님, hold 만료)는 전이가 아니다. payment는 REQUESTED에 남고 400 또는 409로 응답한다. hold 만료로 거부된 경우는 예약 만료 연쇄(REQUESTED → FAILED, PAYING → EXPIRED)가 즉시 실행된다. 토스 결제는 confirm 전이므로 10분 뒤 토스 쪽에서 스스로 만료된다.

### 4. 트랜잭션 경계

- payment 전이와 그에 따른 예약 전이는 **한 트랜잭션**이다. 둘 다 조건부 UPDATE(`WHERE status = :from`)로 실행한다.
- 예약 쪽 조건부 UPDATE의 영향 행이 0이면(예: 예약이 이미 EXPIRED), payment 전이는 그대로 커밋하고 예약은 건드리지 않는다. 이 경우는 정확히 UNKNOWN → CONFIRMED의 보상 분기이며, 같은 트랜잭션 안에서 보상 취소 작업을 outbox에 적재한다(`CompensationRequested`). 실제 토스 취소 호출은 janitor가 한다.
- 토스 API 호출은 트랜잭션 밖에서 한다. 호출 전 REQUESTED → CONFIRMING을 커밋하고, 응답 후 CONFIRMING → 결과를 커밋한다. 응답 처리 중 앱이 죽으면 CONFIRMING에 남고, janitor가 30초 뒤 UNKNOWN으로 옮겨 재조회한다.

### 5. 웹훅 적용 규칙

웹훅은 inbox에 저장만 하고 200을 반환한다. 적용은 별도 워커가 한다.

토스 상태 → 내부 상태 매핑:

| 토스 status | 내부 상태 | 비고 |
|---|---|---|
| READY · IN_PROGRESS | 적용 안 함 | 결제위젯 진행 중. 기록만 |
| DONE | CONFIRMED | |
| ABORTED · EXPIRED | FAILED | |
| CANCELED · PARTIAL_CANCELED | CANCELLED | 1차는 전액 취소만 |
| WAITING_FOR_DEPOSIT | 적용 안 함 | 카드 단일이라 발생하지 않음. 발생 시 알림 |

적용 조건은 두 가지를 모두 만족해야 한다.

1. 상태 순위가 커져야 한다. 순위: REQUESTED 0 · CONFIRMING 1 · UNKNOWN 1 · CONFIRMED 2 · FAILED 2 · CANCELLED 3.
2. (현재 상태 → 이벤트 상태)가 3번 표의 행이어야 한다. 예: FAILED에서 CANCELLED 웹훅은 순위는 크지만 행이 없으므로 폐기.

폐기는 `webhook_stale_count`, 같은 `eventId` 재수신은 `webhook_duplicate_count`로 센다. 웹훅은 `orderId`로 attempt 행을 찾는다(attempt마다 orderId가 다르므로 과거 attempt의 웹훅이 현재 attempt에 적용되지 않는다). 서명 검증 실패는 저장하지 않고 401로 응답한다.

### 6. 두 애그리거트 사이의 불변식

1. 예약 CONFIRMED이면 반드시 결제 CONFIRMED가 존재한다. 역은 성립하지 않아도 된다(보상 대기 중일 수 있다).
2. 결제 CONFIRMED이면서 예약 EXPIRED인 조합(승인·만료 레이스)은 UNKNOWN 5분 경과 경로에서만 생길 수 있고, 생기면 janitor가 60초 안에 토스 취소로 보상한다. 발생은 `confirm_expire_race_occurred`(이벤트 기준), 잔존은 `confirm_expire_race_remaining`(상태 기준)으로 따로 센다. 토스 취소가 반복 실패하면 `compensation_failed_count`로 분리해 리포트 "미해결" 섹션에 올리고, 잔존 지표에서는 제외한다.
3. 예약 하나에 활성 결제는 최대 하나다. FAILED 후에만 새 attempt를 만든다.
4. 모든 전이는 같은 트랜잭션에서 outbox에 이벤트를 남긴다. 이벤트 이름은 2번·3번 표의 부수효과 열에 있다. 상태 테이블의 전이 수와 outbox 행 수의 차이는 `state_event_mismatch_count`로 검증한다(CDC 유실을 세는 `event_loss_count`와 다르다).
5. 전이는 조건부 UPDATE로 실행하고 영향 행 수가 0이면 409다. 읽고 나서 쓰는 사이의 경합은 이 한 줄이 막는다. 예외는 4번 절의 payment → 예약 부수효과뿐이다. 그 경우 payment 전이는 커밋하고 예약 쪽 0행은 보상 경로로 보낸다.

### 7. hold TTL과 승인창 정합의 요약

- 돈이 빠지는 순간은 confirm 호출이다. confirm 전에는 토스 결제가 미승인 상태로 남고, 10분이 지나면 토스가 스스로 만료시킨다. 따라서 **confirm 호출 시점에 hold가 유효한지**만 검사하면 된다.
- confirm 호출이 시작된 뒤에는 예약이 PAYING + CONFIRMING(또는 UNKNOWN)이므로 1번 규칙에 따라 좌석이 HELD로 유지되고, janitor도 2번 규칙에 따라 만료시키지 않는다. 응답이 12분을 넘겨 도착해도 좌석은 아직 이 회원의 것이다. 그래서 응답이 늦었다는 이유로 확정을 거부하지 않는다.
- 유일한 예외는 토스 조회가 5분 이상 계속 실패해 좌석을 푼 뒤 승인이 확인되는 경우이고, 그때는 보상 취소한다(불변식 2).

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 예약·결제 단일 상태머신 | 재시도(FAILED → 새 attempt)와 보상(CONFIRMED인데 EXPIRED)을 한 상태 집합으로 표현하면 상태 수가 곱으로 늘어남. 친구 레포와도 중복 |
| confirm 응답 시점에 hold 만료면 확정 거부 | 돈은 빠졌는데 예약은 PAYING에 갇히고 좌석은 AVAILABLE로 파생되어 이중 판매. 리뷰에서 발견한 결함 |
| PAYING도 만료 시각으로 좌석을 풀기 | confirm 호출 중 다른 회원이 같은 좌석을 잡을 수 있음(오버셀) |
| Spring Statemachine | 2026-07 아카이브. sealed class + 조건부 UPDATE로 충분 |
| 좌석에 상태 컬럼 | 락 전략 비교 시 전략마다 다른 갱신 경로가 생겨 검증 쿼리가 갈라짐 |
| 웹훅을 수신 즉시 동기 적용 | 10초 응답 제한과 적용 로직의 DB 경합이 섞임. inbox 분리가 표준 |
| attempt마다 같은 orderId | 과거 attempt의 웹훅이 현재 attempt에 적용될 수 있고, 토스가 처리된 orderId 재사용을 거부할 수 있음 |

## 결과

- 좋아지는 것: 테스트 목록이 이 표의 행에서 바로 나온다. 면접에서 "왜 UNKNOWN이 필요한가", "confirm 중에 hold가 만료되면"을 표 한 줄로 답한다.
- 감수하는 것: PAYING 예약은 결제가 끝날 때까지(최대 UNKNOWN 5분) 좌석을 붙잡는다. 결제를 시작만 하고 떠난 봇의 좌석은 12분 + 연쇄 처리 뒤에 풀린다. 전이표를 코드와 동기화하는 비용은 전이 함수를 한 파일에 모아 표와 1:1로 대응시켜 줄인다.
- 다시 검토할 조건: 1.5차 대기자 승계에서 CANCELLED 좌석이 즉시 다른 회원의 HOLDING으로 넘어가는 "hold 체인"이 들어오면 1번 파생 규칙에 승계 우선권을 추가한다.

## 증명

- `domain-booking`: 예약 전이표의 모든 (from, to) 쌍에 대한 테스트. 허용 전이는 부수효과와 outbox 이벤트를, 비허용 전이는 409를 검증
- `domain-payment`: 결제 전이표 테스트 + 웹훅 매핑·순위·행 존재 테스트(중복·역순·지연·과거 attempt)
- Fake PG 주입 테스트: (a) 응답이 hold 만료 후 도착 → CONFIRMED, 오버셀 0 (b) 타임아웃 → UNKNOWN → 재조회 → CONFIRMED (c) 재조회 5분 실패 → EXPIRED → 승인 확인 → 보상 취소
- M3 리포트: `confirm_expire_race_occurred`·`confirm_expire_race_remaining`, `webhook_stale_count`, `webhook_duplicate_count`, `state_event_mismatch_count`
