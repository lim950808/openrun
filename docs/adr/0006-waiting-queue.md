# ADR-0006 대기열: ZSET 순번, 입장 스케줄러, 입장 토큰

- 상태: 제안
- 날짜: 2026-11-12
- 관련: [ADR-0003](0003-jpa-jdbc-boundary.md) `queue_*`, [ADR-0004](0004-state-transitions.md), [요구사항](../requirements.md) §1 입장 속도·입장 토큰, M2-1~M2-4, [지표](../metrics.md) M2, [용어](../glossary.md) 대기열

## 맥락

M2는 오픈 시각에 몰리는 요청을 순서대로 좌석 API에 들여보내는 최소 대기열이다. 측정해야 하는 것은 세 가지다. 새로고침을 반복해도 순번이 뒤로 밀리지 않는가(M2-3), 늦게 들어온 사람이 먼저 입장하지 않는가(`fifo_violation_rate`), 입장 전에 떠난 사람이 얼마나 되는가(`abandon_rate`, ADR-0002의 복원 검증 대상). 그리고 좌석 API는 입장 토큰 없이는 열리지 않아야 하며 토큰 검증은 Redis를 거치지 않아야 한다(M2-2). 폴링은 새로고침 폭풍 시나리오에서 초당 5,000건까지 올라가므로 폴링 한 번이 DB에 쓰기를 남겨서는 안 된다.

## 결정

### 1. 대기열 상태는 Redis에, 기록은 `queue_admission`에 둔다

| 키 | 타입 | 내용 |
|---|---|---|
| `queue:{showtimeId}` | ZSET | member = `memberId`, score = 진입 시각(Redis 서버 `TIME` 마이크로초). 순번은 `ZRANK` |
| `queue:{showtimeId}:polled` | HASH | `memberId` → 마지막 폴링 시각(ms). 폴링은 이 해시만 갱신한다 |
| `admitted:{showtimeId}:{memberId}` | STRING | 발급된 입장 토큰. TTL = 입장 토큰 유효 시간(10분). 폴링이 입장 여부를 여기서 본다 |
| `queue:showtimes` | SET | 진입이 한 번이라도 있었던 회차 id. 입장 스케줄러가 순회할 대상. 진입 Lua가 회차 키와 함께 쓰므로 단일 노드 Redis 전제(Cluster면 슬롯이 갈린다) |

진입은 Lua 한 번으로 `ZADD NX`와 `TIME`을 묶는다. 점수를 서버 시각으로 쓰는 이유는 앱 인스턴스가 여러 개여도 시계가 하나이기 때문이고, `NX`인 이유는 **재진입(새로고침)이 기존 점수를 덮어쓰지 않아야** 순번이 유지되기 때문이다(M2-3). 진입 시각이 곧 순번이므로 `fifo_violation_rate`의 "먼저 들어온 사람"은 이 점수로 판정한다.

`queue_admission`(JDBC, ADR-0003의 `queue_*`)은 진입마다 1행을 쓴다. 컬럼은 `showtime_id` · `member_id` · `entered_at`(= ZSET 점수) · `last_polled_at` · `admitted_at` · `admitted_seq` · `abandoned_at` · `token_expires_at`. 폴링은 이 테이블을 건드리지 않고, 입장 스케줄러가 입장·이탈을 판정하는 시점에 Redis 해시의 마지막 폴링 시각을 옮겨 적는다. `abandon_rate`와 `fifo_violation_rate`의 app 측 원천은 이 테이블이다.

### 2. 입장은 1초 틱의 단일 스케줄러가 한다

`AdmissionScheduler`는 ShedLock(`queue-admission`)으로 인스턴스 하나만 돌며 1초마다 `queue:showtimes`의 각 회차에 대해:

1. `open_at <= now()`가 아니면 건너뛴다. 오픈 전에는 순번만 있고 입장은 없다.
2. 회차별 Bucket4j 버킷(용량 = 초당 입장 인원, greedy refill)에서 이번 틱의 **입장 예산**을 꺼낸다.
3. 예산이 남아 있는 동안 `ZRANGE 0 0 WITHSCORES`로 **머리를 보고** 판정한 뒤 Lua 한 번으로 꺼낸다. 스케줄러 하나만 회원을 제거하므로 보고 나서 꺼내도 안전하다. `ZPOPMIN`을 쓰지 않는 이유는 꺼낸 뒤 `admitted:` 키를 쓰기 전까지의 틈에 폴링이 들어오면 "대기 중도 입장도 아님"으로 보여 클라이언트가 재진입해 뒤로 밀리기 때문이다.
   - `max(entered_at, last_polled_at) < now() - 30s`이면 **이탈**. Lua로 `ZREM` + 폴링 해시 `HDEL`, DB에 `abandoned_at`을 쓰고 `QueueAbandoned`를 남긴다. 예산은 쓰지 않는다. 폴링을 멈춘 사람에게 입장권을 주면 좌석 선택 시간(10분)만큼 입장 슬롯이 낭비되기 때문이다.
   - 아니면 **입장**. 입장 토큰을 발급해 Lua 한 번으로 `ZREM` + `HDEL` + `SET admitted:{showtimeId}:{memberId} EX ttl`을 하고, `admitted_at` · `admitted_seq`(Postgres 시퀀스 `queue_admission_admitted_seq`, 틱을 넘어 단조 증가해야 `fifo_violation_rate`를 계산할 수 있다) · `token_expires_at`을 쓰고 `QueueAdmitted`를 남긴다. 예산 1을 쓴다.
4. 한 틱에서 꺼낸 행들은 회차마다 DB 트랜잭션 하나로 쓴다. Redis 제거는 트랜잭션 밖이므로 DB 쓰기가 실패하면 그 틱의 회원은 ZSET에서 빠졌지만 입장 기록이 없는 상태가 될 수 있다. 이 경우 폴링이 `NOT_IN_QUEUE`를 돌려주고 클라이언트가 재진입한다(뒤로 밀림). 횟수는 `queue_admission`에서 `admitted_at`도 `abandoned_at`도 없는데 ZSET에도 없는 행(`lost`)으로 센다. 반대로 진입 INSERT가 실패해 ZSET에는 있는데 행이 없는 회원은 스케줄러가 판정 시점에 ZSET 점수로 행을 만들어 준다. 보상 재시도는 1.5차 janitor 확장 범위다.

머리부터 꺼내므로 FIFO 위반은 구조적으로 0이어야 한다. 지표는 "0이어야 하는 값이 0임"을 매주 증명하는 용도다. 입장 속도 `admission_rate_actual`은 입장 수 ÷ (첫 입장부터 마지막 입장까지의 초, 양 끝 포함)이다. 1초 틱이라 양 끝을 빼면 한 틱만큼 과대평가된다.

### 3. 토큰은 HS256 JWT 두 종류이고 검증은 상태가 없다

| 토큰 | 용도 | 클레임 | 유효 |
|---|---|---|---|
| 대기 토큰 (`visitor token`) | `GET /queue/me` 폴링 | `typ=visitor`, `sub=memberId`, `stid=showtimeId` | 3시간 |
| 입장 토큰 (`access token`) | 좌석 조회·hold | `typ=access`, `sub`, `stid`, `jti`, `exp` | 10분 (`openrun.queue.access-token-ttl`) |

서명 키는 `openrun.queue.token-secret`(환경변수 `OPENRUN_TOKEN_SECRET`). 로컬 기본값은 `dev-only-...` 접두사의 고정 문자열이고 CI·벤치 워크플로는 실행마다 난수로 만든다. 검증은 서명과 `exp`·`typ`·`stid`만 보며 Redis를 읽지 않는다(M2-2). 만료 토큰은 `401 ACCESS_TOKEN_EXPIRED`, 없거나 깨지면 `401 ACCESS_TOKEN_REQUIRED` · `401 ACCESS_TOKEN_INVALID`, 다른 회차 토큰이면 `401 ACCESS_TOKEN_INVALID`다. 이 401들의 합이 `unauthorized_seat_calls`다. 카운터는 프로세스 전역이고 회차 태그가 없다.

입장 토큰이 필요한 API는 `GET /showtimes/{id}/seats`와 `POST /showtimes/{id}/holds`다. `DELETE /reservations/{id}`는 예약 소유자 검증으로 보호하므로 입장 토큰 **또는** `X-Member-Id`를 받는다(결제 중 토큰이 만료돼도 취소할 수 있어야 한다, 요구사항 §1). `openrun.queue.require-access-token=false`이면 좌석 API도 `X-Member-Id`로 돌아간다. 대기열 없이 hold만 때리는 M1 벤치마크와 M1 테스트가 이 모드를 쓴다. 기본값은 `true`다.

만료 후 재진입(M2-4): `admitted:` 키가 TTL로 사라진 뒤 진입하면 ZSET에 새 점수로 들어가고 `queue_admission`에 새 행이 생긴다. 입장 토큰이 아직 유효한 상태에서 다시 진입하면 줄을 세우지 않고 같은 토큰을 돌려준다.

### 4. API

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| `POST` | `/api/showtimes/{id}/queue` | `X-Member-Id` | `201 {status: WAITING, visitorToken, ahead, enteredAt}`. 재진입은 `200`에 같은 `enteredAt`. 이미 입장 상태면 `200 {status: ADMITTED, accessToken, accessTokenExpiresAt}`. 진입 허용 전(`open_at − 10분` 이전)은 `409 QUEUE_NOT_OPEN` |
| `GET` | `/api/showtimes/{id}/queue/me` | Bearer 대기 토큰 | `200 {status: WAITING, ahead}` · `{status: ADMITTED, accessToken, accessTokenExpiresAt}` · `{status: NOT_IN_QUEUE}`(이탈 처리됐거나 틱 실패). `ahead`는 `ZRANK` |
| `GET` | `/api/admin/showtimes/{id}/queue-integrity` | 없음(운영) | `entered` · `admitted` · `abandoned` · `waiting` · `abandon_rate` · `fifo_violation_rate` · `admission_delay_p50_s` · `admission_delay_p99_s` · `admission_rate_actual` · `unauthorized_seat_calls`. 점검 시점에 ZSET에 남아 있고 30초 넘게 폴링이 없는 회원은 `abandoned`에 더해 센다(쓰지는 않는다) |

### 5. 이벤트

`QueueEntered`(진입, 재진입은 내지 않음) · `QueueAdmitted` · `QueueAbandoned`(스케줄러 판정 시). aggregate는 `queue_admission`, id는 그 행의 id. `QueuePolled` 샘플링은 2차 마트가 필요로 할 때 넣는다. 1차 `abandon_rate`는 app 측(`queue_admission`)에서만 계산한다.

### 6. 설정값

| 키 | 기본 | 환경변수 |
|---|---|---|
| `openrun.queue.admissions-per-second` | 100 | `OPENRUN_QUEUE_ADMISSIONS_PER_SECOND` |
| `openrun.queue.entry-lead` | PT10M | `OPENRUN_QUEUE_ENTRY_LEAD` |
| `openrun.queue.abandon-after` | PT30S | `OPENRUN_QUEUE_ABANDON_AFTER` |
| `openrun.queue.access-token-ttl` | PT10M | `OPENRUN_QUEUE_ACCESS_TOKEN_TTL` |
| `openrun.queue.tick-ms` | 1000 | `OPENRUN_QUEUE_TICK_MS` |
| `openrun.queue.require-access-token` | true | `OPENRUN_QUEUE_REQUIRE_ACCESS_TOKEN` |
| `openrun.queue.token-secret` | dev-only 고정값 | `OPENRUN_TOKEN_SECRET` |

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 점수를 `INCR` 카운터로 | 순번 충돌은 없지만 "진입 시각이 순번"이라는 정의와 `admission_delay` 계산에 시각이 따로 필요해 두 값을 들고 다녀야 함. 마이크로초 시각 충돌은 member id 사전순으로 깨지며 측정에 영향 없음 |
| 폴링마다 `queue_admission.last_polled_at` UPDATE | 새로고침 폭풍에서 초당 5,000 UPDATE. 폴링은 Redis 해시만 쓰고 스케줄러가 옮겨 적는다 |
| 이탈자도 입장시키고 사후 집계만 | 입장 슬롯이 10분씩 낭비돼 실제 입장 속도가 설정값과 달라짐. `admission_rate_actual ±5%` 목표와 충돌 |
| 입장 토큰을 불투명 문자열 + Redis 조회 | M2-2가 Redis 없는 검증을 요구. 1.5차 Go 입장 게이트 비교 실험도 무상태 검증이 전제 |
| JWT를 직접 구현(HMAC 40줄) | 라이브러리 없이 가능하지만 면접에서 "왜 표준 구현을 안 썼나"가 더 설명하기 어려움. nimbus-jose-jwt는 Spring Security가 쓰는 구현이라 의존 하나로 끝남 |
| Spring Security 도입 | 필터 체인·세션·CSRF 설정이 1차 범위를 넘음. 1.5차 봇 방어에서 재검토 |
| `ZPOPMIN`으로 꺼낸 뒤 판정 | 꺼낸 순간부터 `admitted:` 키를 쓸 때까지 폴링이 `NOT_IN_QUEUE`를 봄. 머리를 보고 Lua로 제거+입장 기록을 한 번에 한다. 1명씩 처리하는 비용은 초당 100회로 무시 가능 |

## 결과

- 좋아지는 것: 폴링 경로가 Redis 전용이라 새로고침 폭풍이 DB에 닿지 않는다. 입장 순서가 점수 순이라 FIFO 위반 0을 매주 증명한다. 좌석 API 인증이 무상태라 M1 벤치 결과와 비교 가능하다.
- 감수하는 것: 틱 중간 DB 실패 시 회원이 줄에서 빠지는 경우가 있다(§2-4, 재진입으로 복구). 버킷이 인스턴스 메모리에 있어 스케줄러 다중화는 1.5차 ShedLock 확장과 같이 간다. 토큰 폐기가 없으므로 유효 시간이 곧 권한 기간이다.
- 다시 검토할 조건: 회차가 동시에 여러 개 열리거나 입장 속도를 회차별로 달리해야 하면 설정을 회차 테이블로 옮긴다. 사람 참여(1.5차)에서 토큰 탈취가 문제가 되면 지문·Turnstile과 함께 토큰에 클라이언트 바인딩을 넣는다.

## 증명

- `QueueApiTest`: M2-1 진입·순번·폴링, M2-2 토큰 없이 401 및 Redis `FLUSHALL` 뒤에도 토큰 검증 통과, M2-4 만료 토큰 401·재진입 새 순번
- `AdmissionSchedulerTest`: 오픈 전 입장 0, 틱당 입장 인원 = 설정값, 30초 무폴링 회원은 이탈 처리되고 예산을 쓰지 않음, FIFO 위반 0
- `QueueStormTest`: 회원 다수가 0.2초 간격으로 동시 폴링해도 `rank_drift_count` 0
- `TokenCodecTest`: 발급·검증·만료·서명 불일치·회차 불일치
- `load/k6/queue-storm.js` + `GET /queue-integrity`: 새로고침 폭풍 1,000 VU에서 `rank_drift_count` 0, `rank_api_p99_ms` 100 이하
- `load/k6/weekly-openrun.js`: 진입 → 폴링(이탈 확률 적용) → 입장 → 좌석 선택 → hold 흐름이 시나리오 yaml 파라미터로 돈다
