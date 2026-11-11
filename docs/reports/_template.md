# 주간 오픈런 리포트 #N — YYYY-MM-DD (토)

<!-- 복사해서 docs/reports/YYYY-MM-DD.md 로 저장. 지표명과 수식은 docs/metrics.md, 머리말 차원은 metrics.md 마지막 절. -->

## 머리말

| 차원 | 값 |
|---|---|
| `report_no` / `run_date` | #N / YYYY-MM-DD |
| `app_commit` | `abc1234` |
| `scenario_file` / `scenario_hash` | `load/scenarios/weekly-default.yaml` / `blob-hash` |
| `strategy` | `SKIP_LOCKED` |
| `runner_spec` | GitHub Actions ubuntu-latest · 4 vCPU · 16GB · kernel x.y |
| `runs` | 벤치마크 3회 중앙값 (최소~최대) + 주간 오픈런 1회 |
| `source_label` | synthetic 100% |
| 이번 주 변경 | PR #n 한 줄 요약 |

## 한 줄 요약

이번 주에 무엇이 달라졌고, 어느 숫자가 그것을 증명하는가. 한 문장.

## 정합성 (0이어야 하는 것)

| 지표 | 벤치마크 3회 합계 | 주간 오픈런 | 목표 |
|---|---|---|---|
| `oversell_count` | | | 0 |
| `confirm_expire_race_remaining` | | | 0 |
| `compensation_failed_count` | | | 0 |
| `state_event_mismatch_count` | | | 0 |
| `illegal_transition_count` | | | 보고 |

## 성능 (벤치마크 중앙값)

| 지표 | 값 (최소~최대) | 지난주 | 목표 |
|---|---|---|---|
| `hold_p99_ms` | | | ≤ 200 |
| `hold_throughput_rps` | | | 보고 |
| `rank_api_p99_ms` | | | ≤ 100 |
| `expire_lag_s` (p99) | | | ≤ 60 |

### 전략 비교 (M1 이후 유지)

| `strategy` | `oversell_count` | `hold_p99_ms` | `hold_throughput_rps` |
|---|---|---|---|
| NAIVE | | | |
| FOR_UPDATE | | | |
| SKIP_LOCKED | | | |
| CONDITIONAL_UPDATE | | | |
| REDIS_LUA | | | |

## 대기열 (M2 이후)

| 지표 | 값 | 시나리오 설정 | 목표 |
|---|---|---|---|
| `abandon_rate` | | 15% | 복원 검증 |
| `fifo_violation_rate` | | | ≤ 1% |
| `admission_delay_p50_s` / `p99_s` | | | 보고 |
| `rank_drift_count` | | | 0 |

## 결제 (M3 이후)

| 지표 | 값 | 목표 |
|---|---|---|
| `hold_to_confirm_rate` | | 포기율 12%로 역산한 기대값과 비교 |
| `confirm_expire_race_occurred` | | Fake PG 주입량과 비교 |
| `unknown_dwell_p99_s` | | ≤ 60 |
| `webhook_ack_p99_ms` | | ≤ 1,000 |
| `webhook_stale_count` / `webhook_duplicate_count` | | 보고 |

## 퍼널 (2차 이후, `source_label` 명시)

| 단계 | 인원 | 전환율 |
|---|---|---|
| enqueued | | 100% |
| admitted | | |
| held | | |
| paying | | |
| confirmed | | |

복원 검증: `recovery_error_pp` = (abandon / payment_abandon / macro) 각각.

## 이번 주 배운 것

- 문제 재현 테스트 → 해결 → 숫자 → 한계. 각 항목에 PR 링크.

## 미해결

- 보상 실패, 수동 처리 대기, 다음 주로 넘기는 것.

## 재현

```bash
git checkout <app_commit>
./gradlew build
k6 run -e SCENARIO=load/scenarios/weekly-default.yaml load/k6/weekly.js
```
