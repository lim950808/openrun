# openrun 용어집

문서·코드·리포트·면접에서 같은 말을 쓰기 위한 표. 코드 식별자 열이 테이블명·클래스명·API 경로의 기준이다. 새 용어는 여기에 먼저 추가한다.

## 카탈로그 (JPA 영역)

| 한글 | 코드 식별자 | 정의 |
|---|---|---|
| 공연 | `Show`, `show` | 판매 단위의 상위 개념. 1차는 1개 |
| 회차 | `Showtime`, `showtime` | 특정 일시의 공연. 좌석 재고는 회차 단위로 존재. `Session`은 HTTP 세션과 혼동되므로 쓰지 않는다 |
| 공연장 | `Venue` | 좌석 배치도를 가진 장소 |
| 좌석 배치도 | `SeatMap` | 공연장의 좌석 집합과 좌표. SVG 좌석맵의 입력 |
| 좌석 | `Seat`, `seat_id` | 배치도상의 물리 좌석. 회차와 무관한 마스터 |
| 좌석 등급 | `SeatGrade` (`VIP`·`R`·`S`) | 가격을 결정하는 등급 |
| 회원 | `Member` | 간이 회원. 1차는 닉네임과 id만 |
| 주최자 | `Organizer` | 공연을 등록하는 주체. 1차는 시드 데이터 |
| 정책 | `Policy` | 취소수수료 등 규칙 테이블 (1.5차) |

## 예약 (JDBC 영역, `domain-booking`)

| 한글 | 코드 식별자 | 정의 |
|---|---|---|
| 선점 | `Hold`, `seat_hold` | 회차의 특정 좌석을 한 회원이 TTL 동안 독점하는 것. 좌석 단위. `seat_hold` 테이블은 회차 생성 시 좌석 수만큼 미리 채우는 재고 행이고, 선점은 그 행을 조건부로 차지하는 것(ADR-0005) |
| 예약 좌석 | `reservation_seat` | 예약이 잡았다고 믿는 좌석과 가격 스냅샷. 오버셀 검증의 기준 테이블 |
| 선점 만료 시각 | `expires_at` | hold 생성 + 12분. 연장 없음 |
| 예약 | `Reservation`, `reservation` | 한 회원의 hold 묶음(1~2석)과 그 상태. 결제의 대상. `Booking`은 모듈명(`domain-booking`)에만 쓰고 애그리거트 이름으로는 쓰지 않는다 |
| 예약 상태 | `ReservationStatus` | `HOLDING` · `PAYING` · `CONFIRMED` · `EXPIRED` · `CANCELLED` |
| 좌석 상태 | `SeatStatus` | `AVAILABLE` · `HELD` · `SOLD`. 회차 좌석의 파생 상태. PAYING 예약의 좌석은 만료 시각과 무관하게 `HELD` (ADR-0004) |
| 가격 스냅샷 | `price_snapshot` | hold 시점의 좌석 가격. 결제 금액 검증과 매출 손실 추정에 사용 |
| 확정 | `confirm` (예약 문맥) | 결제 승인 수신 후 예약을 `CONFIRMED`로 바꾸고 좌석을 `SOLD`로 만드는 것 |
| 만료 | `expire` | TTL 경과로 hold를 풀고 예약을 `EXPIRED`로 바꾸는 것 |
| 해제 | `release` | 좌석이 `AVAILABLE`로 돌아가는 것. 좌석 상태는 파생이므로 예약이 EXPIRED·CANCELLED가 되면 자동으로 해제된다. 결제 실패는 hold를 유지하므로 해제가 아니다 |
| 활성 예약 | `active reservation` | 상태가 HOLDING 또는 PAYING인 예약. 1인 1건 제한의 기준. 오버셀 판정은 여기에 CONFIRMED를 더한 집합으로 한다 |
| 재고 | `Inventory` | 선점 가능한 자원의 추상화. 1차 구현체는 지정석(`SeatInventory`) |
| 락 전략 | `LockStrategy` | M1 비교 대상: `NAIVE` · `FOR_UPDATE` · `SKIP_LOCKED` · `CONDITIONAL_UPDATE` · `REDIS_LUA`. 요청 헤더 `X-Hold-Strategy`는 벤치마크 설정(`openrun.hold.allow-strategy-override`)에서만 유효 |
| 재고 정합성 점검 | `integrity` | `GET /api/admin/showtimes/{id}/integrity`. `oversell_count` 등 실행 종료 후 검증 지표 |
| 오버셀 | `oversell` | 같은 회차·좌석에 활성 예약이 2건 이상인 상태 |

## 대기열 (`domain-booking`, Redis)

| 한글 | 코드 식별자 | 정의 |
|---|---|---|
| 대기열 | `Queue`, `queue:{showtimeId}` | 오픈 시 좌석 API 접근을 순서대로 허용하는 ZSET |
| 진입 | `enqueue` | 대기열에 들어가는 것. 진입 시각이 순번 |
| 순번 | `rank` | ZRANK 값. 0부터 시작 |
| 대기 토큰 | `visitor token` | 진입 시 발급. 순번 조회에만 사용 |
| 입장 | `admission`, `admit` | 대기열에서 꺼내 입장 토큰을 주는 것 |
| 입장 속도 | `admission_rate` | 초당 입장 인원. Bucket4j로 제어 |
| 입장 토큰 | `access token` | 좌석 API 호출 권한. JWT, 10분 |
| 이탈 | `abandon` | 입장 전에 폴링을 멈추는 것 |
| FIFO 위반 | `fifo_violation` | 늦게 진입한 사람이 먼저 입장한 경우 |
| 새로고침 폭풍 | `refresh storm` | 폴링 간격 0.2초 봇 시나리오 |
| 입장 기록 | `queue_admission` | 회원별 진입·입장·마지막 폴링 시각을 적는 JDBC 테이블. 대기열 자체는 Redis에 있고, 이 테이블이 ADR-0003의 `queue_*`다. 이탈률·FIFO 위반률의 app 측 원천 |

## 결제 (JDBC 영역, `domain-payment`)

| 한글 | 코드 식별자 | 정의 |
|---|---|---|
| 결제 | `Payment`, `payment` | 예약 1건에 대한 결제 시도 1건. 실패 후 재시도는 새 `attempt` |
| 결제 상태 | `PaymentStatus` | `REQUESTED` · `CONFIRMING` · `CONFIRMED` · `FAILED` · `UNKNOWN` · `CANCELLED` |
| 주문번호 | `orderId` | 토스에 넘기는 식별자. `{reservationId}-{attempt}`로 attempt마다 다르다. 웹훅은 이 값으로 attempt 행을 찾는다. `Order`라는 애그리거트는 만들지 않는다 |
| 결제키 | `paymentKey` | 토스가 발급하는 결제 식별자 |
| 승인 | `confirm` (결제 문맥) | 토스 `/v1/payments/confirm` 호출. 이 호출 전까지 돈은 빠지지 않는다 |
| 승인창 | `confirm window` | 결제위젯 결제 후 confirm 가능한 10분 |
| 멱등키 | `Idempotency-Key` | 토스 confirm 재시도 키. 값은 `{reservationId}:{attempt}` |
| 웹훅 인박스 | `payment_event_inbox` | 토스 웹훅을 받은 즉시 저장하는 테이블. 처리와 수신을 분리 |
| 과거 상태 폐기 | `stale event discard` | 현재 상태보다 앞선 상태를 말하는 이벤트를 적용하지 않는 것 |
| 미확정 | `UNKNOWN` | confirm 응답을 못 받아 승인 여부를 모르는 상태 |
| 재조회 | `reconcile` | 토스 조회 API로 `UNKNOWN`을 확정짓는 것 |
| 청소부 | `janitor` | 세 경로를 맡는 스케줄러: hold 만료 처리, `CONFIRMING` 30초 초과 → `UNKNOWN` 전환과 재조회, 승인·만료 레이스 보상 취소. 결제 진행 중(CONFIRMING·UNKNOWN 5분 미만)인 PAYING 예약은 만료시키지 않는다 |
| 승인·만료 레이스 | `confirm-expire race` | 결제는 승인됐는데 예약이 이미 EXPIRED인 상태. UNKNOWN 5분 경과 후 좌석을 푼 경우에만 생길 수 있다. 보상 취소 대상. 발생 `confirm_expire_race_occurred`, 잔존 `confirm_expire_race_remaining` |
| 보상 | `compensate` | 레이스 발생 시 토스 취소를 호출해 되돌리는 것 |
| 가짜 PG | `FakePg` | 타임아웃·지연·오류를 주입하는 토스 대역 |

## 데이터·리포트

| 한글 | 코드 식별자 | 정의 |
|---|---|---|
| 아웃박스 | `outbox` | 도메인 이벤트를 같은 트랜잭션으로 적재하는 테이블. CDC 입력 |
| 도메인 이벤트 | `ReservationHeld` 등 | 과거형 이름. 예약: `ReservationHeld` · `ReservationPaymentStarted` · `ReservationPaymentFailed` · `ReservationConfirmed` · `ReservationExpired` · `ReservationCancelled`. 결제: `PaymentRequested` · `PaymentConfirming` · `PaymentConfirmed` · `PaymentFailed` · `PaymentUnknown` · `PaymentCancelled` · `CompensationRequested`. 대기열: `QueueEntered` · `QueueAdmitted` · `QueuePolled`(샘플링). 토스 웹훅 이벤트는 `PaymentEvent`로 구분해 부른다 |
| 오픈런 | `open run` | 오픈 시각에 몰리는 예매 경쟁 |
| 주간 오픈런 | `weekly open run` | 매주 토요일 20:00 자동 실행되는 가상 오픈런 |
| 시나리오 | `scenario`, `load/scenarios/*.yaml` | 봇 행동 파라미터 파일 |
| 매크로 | `macro bot` | 비정상적으로 빠른 봇. 봇 방어 대상 |
| 리포트 | `report`, `docs/reports/YYYY-MM-DD.md` | 주간 오픈런 결과 문서 |
| 데이터 출처 라벨 | `source_label` | `synthetic` · `human` · `mixed` |
| 복원 오차 | `recovery_error_pp` | 마트가 계산한 회원 단위 확률과 시나리오 설정값의 차이(%p) |
| 마트 | `mart` | dbt로 만든 분석 테이블 |
| 리니지 | `lineage` | 데이터가 어디서 와서 어디로 가는지의 그래프 |
| 리플레이 | `replay` | 한 회차의 이벤트를 시간순으로 다시 보여주는 화면 |

## 쓰지 않는 말

| 피하는 말 | 대신 | 이유 |
|---|---|---|
| Ticket | Reservation, Seat | 좌석인지 예약인지 모호 |
| Order | Reservation (orderId는 토스 파라미터에만) | 커머스 주문과 다름 |
| Session | Showtime | HTTP 세션과 충돌 |
| Booking (애그리거트) | Reservation | 모듈명으로만 사용 |
| Event (도메인 객체) | Show | 도메인 이벤트·PG 이벤트와 충돌 |
| Lock (좌석 문맥) | Hold | 락은 DB·Redis 동기화 수단, hold는 비즈니스 개념 |
| 온톨로지 | 시맨틱 레이어, 지표 정의 레이어 | 로드맵 결정 |
