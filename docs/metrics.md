# openrun 지표 정의

리포트·코드·마트가 같은 이름과 같은 수식을 쓰기 위한 문서. 지표명은 `snake_case`로 고정하고 k6 커스텀 메트릭, 검증 쿼리, dbt 모델, 리포트 표 헤더에 그대로 쓴다. 3차 AI 에이전트가 인용할 수 있는 숫자도 이 목록 안의 것만이다.

## 공통 규칙

1. 측정 환경은 [roadmap.md](roadmap.md) "부하 테스트 환경 규칙"을 따른다. 성능 지표(p99·처리량)는 벤치마크 3회 실행 중앙값을 보고하고 최소·최대를 괄호에 적는다. 건수 지표(오버셀·레이스·웹훅)는 벤치마크 3회 합계와 주간 오픈런 1회를 따로 적는다.
2. 모든 지표에 데이터 출처 라벨을 붙인다. `synthetic` · `human` · `mixed`. 1차는 전부 `synthetic`.
3. 분모가 0이면 `n/a`로 표기하고 0%로 쓰지 않는다.
4. 시각은 UTC로 저장하고 리포트에는 KST로 표시한다.
5. 측정 지점은 세 종류다. **k6**(클라이언트 관측), **app**(앱 로그·DB 검증 쿼리), **mart**(2차 dbt). 같은 이름의 지표가 두 지점에서 나오면 리포트에 둘 다 싣고 차이를 적는다. 이 차이가 2차 복원 검증의 재료다.
6. 정의를 바꾸면 지표명에 버전을 붙인다. 예: `abandon_rate_v2`. 이전 리포트는 다시 계산하지 않는다.

## M1 좌석 선점

| 지표 | 정의 | 분자 / 분모 | 측정 지점 | 목표 |
|---|---|---|---|---|
| `oversell_count` | **잔존.** 점검 시점에 같은 회차·좌석에 PAYING·CONFIRMED 또는 (HOLDING이고 `expires_at > now()`)인 예약이 2건 이상인 좌석 수. `reservation_seat` 기준(ADR-0005 §2) | 해당 좌석 수 / 없음 | app (`GET /api/admin/showtimes/{id}/integrity`) | 0 |
| `oversell_occurred_count` | **발생.** 실행 중 한 번이라도 두 예약의 활성 구간이 겹친 좌석 수. 구간 시작 = `held_at`(claim 성공 직후의 DB 시각; 트랜잭션 시작인 `created_at`은 락 대기 시간을 포함해 거짓 겹침을 만든다), 끝 = CANCELLED면 `updated_at`, EXPIRED면 `least(updated_at, expires_at)`, HOLDING이면 `expires_at`(lazy 재선점 때문에 now()를 쓰면 안 됨). 즉시 해제되는 버스트에서도 오버셀을 놓치지 않는다 | 해당 좌석 수 / 없음 | app (integrity) | 0 (NAIVE는 ≥1이어야 재현 성공) |
| `oversell_overlap_pairs` | 위 정의로 겹친 예약 쌍의 수. 좌석 하나를 때리는 버스트에서는 좌석 수가 최대 1이라 쌍 수로 강도를 본다 | 없음 | app (integrity) | 0 |
| `burst_oversell_occurred` | 같은 좌석에 100 VU가 hold→즉시 release를 반복하는 버스트(`load/k6/bench-m1-burst.js`)의 `oversell_overlap_pairs` (괄호 안은 좌석 수) | 없음 | app, k6 | NAIVE ≥ 1, 나머지 0 |
| `hold_p99_ms` | `POST /showtimes/{id}/holds` 응답 시간 p99. 성공·실패 응답 모두 포함, 네트워크 오류 제외 | 없음 | k6 `http_req_duration{name:hold}` | 200 이하 |
| `hold_throughput_rps` | 초당 hold 성공 응답 수. peak 구간(20:00~20:05) 평균 | 성공 응답 수 / 초 | k6 | 보고만 |
| `hold_success_rate` | hold 요청 중 201 비율 | 201 응답 / 전체 hold 요청 | k6 | 보고만 (좌석 수로 상한 결정됨) |
| `hold_conflict_rate` | 좌석 충돌로 거부된 비율. 오류 코드 `SEAT_TAKEN`만 센다(활성 예약 중복 `ACTIVE_RESERVATION_EXISTS`, `ILLEGAL_TRANSITION`은 제외) | `SEAT_TAKEN` 응답 / 전체 hold 요청 | k6 | 보고만 |
| `expire_lag_s` | `expires_at`부터 janitor가 예약을 EXPIRED로 쓴 시각까지 지연 p99 (좌석은 파생 상태라 `expires_at`에 즉시 AVAILABLE이 되지만, 이벤트와 Redis 키 정리는 janitor가 한다). 실행 종료 시점의 잔여는 `expire_backlog_max_s` | 없음 | app (janitor 로그, integrity) | 60 이하 |
| `illegal_transition_count` | 허용되지 않은 상태 전이 요청으로 409를 반환한 수 | 없음 | app | 보고만 (봇 시나리오 주입량과 비교) |
| `strategy` | 이번 실행의 락 전략 (`NAIVE`·`FOR_UPDATE`·`SKIP_LOCKED`·`CONDITIONAL_UPDATE`·`REDIS_LUA`) | 차원 | 시나리오 | 리포트 표의 행 |

## M2 대기열

| 지표 | 정의 | 분자 / 분모 | 측정 지점 | 목표 |
|---|---|---|---|---|
| `abandon_rate` | 진입했지만 입장 전에 폴링을 멈춘 비율. "멈춤"은 마지막 폴링 후 30초 이상 무요청이고 입장되지 않은 경우. app은 `queue_admission` 테이블, mart는 `QueueEntered`·`QueueAdmitted`·마지막 폴링 이벤트로 계산 | 이탈 회원 수 / 진입 회원 수 | app, mart | 시나리오의 회원당 이탈 확률(15%)과 비교 (복원 검증) |
| `fifo_violation_rate` | 입장된 회원 중, 자신보다 늦게 진입한 회원이 먼저 입장한 경우의 비율 | 위반 회원 수 / 입장 회원 수 | app | 1% 이하 |
| `admission_delay_p50_s`, `admission_delay_p99_s` | 입장 시각 − 진입 시각 (오픈 전 진입은 오픈 시각 기준) | 없음 | app | 보고만 |
| `admission_rate_actual` | 실제 초당 입장 인원 평균 | 입장 수 / 초 | app | 설정값 ±5% |
| `rank_api_p99_ms` | `GET /queue/me` 응답 시간 p99 | 없음 | k6 | 100 이하 |
| `rank_drift_count` | 새로고침 폭풍 시나리오에서 같은 회원의 순번이 직전 응답보다 커진 횟수 (줄어드는 것은 정상) | 없음 | k6 커스텀 | 0 |
| `unauthorized_seat_calls` | 입장 토큰 없이 또는 만료 토큰으로 좌석 API를 호출해 401을 받은 수 | 없음 | app | 보고만 (매크로 행동 지표) |

## M3 결제

| 지표 | 정의 | 분자 / 분모 | 측정 지점 | 목표 |
|---|---|---|---|---|
| `hold_to_confirm_rate` | hold 성공한 예약 중 한 번이라도 CONFIRMED에 도달한 비율 (이후 취소돼도 포함) | CONFIRMED 도달 예약 수 / HOLDING에 도달한 예약 수 | app, mart | 시나리오 포기율로 역산한 기대값과 비교 |
| `confirm_expire_race_occurred` | payment CONFIRMED 전이 시점에 예약이 이미 EXPIRED였던 건수. 이벤트(`PaymentConfirmed`와 당시 예약 상태)로 센다 | 없음 | app (이벤트), mart | 보고만 (Fake PG 주입량과 비교) |
| `confirm_expire_race_remaining` | 검증 시점에 payment CONFIRMED이면서 예약 EXPIRED인 건수. `compensation_failed_count`에 포함된 건은 제외 | 없음 | app 검증 쿼리 | 0 |
| `compensation_failed_count` | 보상 취소를 3회 이상 재시도해도 실패해 수동 처리로 넘긴 건수 | 없음 | app | 0, 발생 시 리포트 "미해결" |
| `unknown_dwell_p99_s` | payment가 UNKNOWN에 머문 시간 p99 | 없음 | app | 60 이하 |
| `unknown_count` | UNKNOWN을 거친 결제 수 | 없음 | app | 보고만 (Fake PG 주입량과 비교) |
| `webhook_ack_p99_ms` | 웹훅 수신부터 200 응답까지 p99 | 없음 | app | 1,000 이하 |
| `webhook_apply_lag_p99_s` | 웹훅 inbox 저장부터 상태 적용까지 p99 | 없음 | app | 5 이하 |
| `webhook_duplicate_count` | eventId 중복으로 폐기한 웹훅 수 | 없음 | app | 보고만 |
| `webhook_stale_count` | 과거 상태라 폐기한 웹훅 수 | 없음 | app | 보고만 |
| `amount_mismatch_count` | 서버 금액 검증에 실패한 confirm 요청 수 | 없음 | app | 주입량과 동일 |
| `idempotent_replay_count` | 같은 Idempotency-Key로 재시도되어 토스 호출 없이 응답한 수 | 없음 | app | 보고만 |
| `cancel_success_rate` | 취소 요청 중 payment CANCELLED와 좌석 복귀가 모두 된 비율 | 성공 / 요청 | app | 100% |

## 1.5차 제품 완성도

| 지표 | 정의 | 분자 / 분모 | 측정 지점 |
|---|---|---|---|
| `cancel_fee_accuracy` | 정책 테이블로 계산한 수수료와 실제 환불액이 일치한 비율 | 일치 / 취소 건수 |app |
| `succession_fill_rate` | 취소로 풀린 좌석 중 대기자 승계로 다시 hold된 비율 | 승계 hold / 취소 좌석 | app |
| `bot_block_rate` | 매크로 시나리오 요청 중 차단된 비율 | 차단 / 매크로 요청 | app |
| `human_vs_bot_success_ratio` | 사람 확정률 ÷ 봇 확정률. `human` 라벨 필요 | 사람 `hold_to_confirm_rate` / 봇 `hold_to_confirm_rate` | app, mart |

## 2차 측정 파이프라인

| 지표 | 정의 | 분자 / 분모 | 측정 지점 | 목표 |
|---|---|---|---|---|
| `recovery_error_pp` | 마트가 추정한 회원 단위 확률과 시나리오 설정값의 차이. 대상은 `abandon_rate`(15%), 결제 포기율(12%), 매크로 비율(5%). 세 값 각각 보고 | \|mart − scenario\| (%p) | mart | 0.5%p 이하 |
| `funnel_rate_{stage}` | 단계별 전환율. stage는 `enqueued → admitted → held → paying → confirmed` | 다음 단계 회원 수 / 이전 단계 회원 수 | mart | 보고만 |
| `estimated_revenue_loss_krw` | hold됐으나 확정되지 않은 좌석의 가격 스냅샷 합. 라벨 `synthetic` 필수 | 없음 | mart | 보고만 ("합성 조건 추정" 표기) |
| `cdc_lag_p99_s` | outbox 적재 시각부터 DuckDB 적재 시각까지 p99 | 없음 | mart | 보고만 |
| `event_loss_count` | outbox의 고유 eventId 수 − 마트의 고유 eventId 수. CDC 구간 유실만 잰다 | 없음 | mart | 0 |
| `event_duplicate_count` | 마트에서 같은 eventId가 2회 이상인 수 | 없음 | mart | 0 |
| `state_event_mismatch_count` | 상태 테이블(reservation·payment)의 전이 기록 수 − 해당 전이의 outbox 이벤트 수. "상태는 바뀌었는데 이벤트가 없는" 경우를 잰다 | 없음 | app 검증 쿼리, mart | 0 |

## 3차 AI 브리핑

| 지표 | 정의 | 분자 / 분모 | 측정 지점 | 목표 |
|---|---|---|---|---|
| `eval_pass_rate` | 평가셋 질문 중 3회 모두 grader를 통과한 비율 (pass^3) | 통과 질문 / 전체 질문 | CI evalTest | 95% 이상 |
| `hallucination_count` | 브리핑에 등장한 숫자 중 마트에 없는 숫자의 수 | 없음 | 출력 가드 | 0 |
| `refusal_rate` | 도메인 외 질문을 거절한 비율 | 거절 / 도메인 외 질문 | evalTest | 100% |
| `briefing_latency_p50_s` | 브리핑 생성 시간 p50 | 없음 | Langfuse | 보고만 |

## 리포트 머리말에 반드시 들어가는 차원

| 차원 | 예 |
|---|---|
| `report_no`, `run_date` | #1, 2026-11-28 |
| `scenario_file`, `scenario_hash` | `weekly-default.yaml`, git blob hash |
| `strategy` | `SKIP_LOCKED` |
| `runner_spec` | GitHub Actions ubuntu-latest, 4 vCPU, 16GB |
| `app_commit` | 짧은 SHA |
| `source_label` | synthetic 100% |
| `runs` | 벤치마크 3회 중앙값 + 주간 오픈런 1회 |
