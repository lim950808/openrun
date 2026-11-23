# 주간 오픈런 리포트

<!-- load/report/weekly_report.py가 매주 다시 쓴다. 손으로 고치지 않는다. -->

매주 토요일 `weekly-openrun` 워크플로가 벤치마크 3회 중앙값과 주간 오픈런 1회 결과로 발행한다(ADR-0008 §3). 지표 정의는 [metrics.md](../metrics.md).

| 날짜 | # | 한 줄 요약 | 리포트 |
|---|---|---|---|
| 2026-11-21 | #1 | 정합성 4지표 모두 0 (벤치 + 주간 오픈런), `hold_p99_ms` 중앙값 300.2 ms (목표 ≤ 200, 생성기 포화 `dropped_ratio` 37.6%라 판정 보류), 주간 오픈런 `admission_rate_actual` 8.4/s (설정 100/s). | [2026-11-21.md](2026-11-21.md) |

- 러너 실행 기록(벤치 원본 표): [runs/](runs/)
- 템플릿: [_template.md](_template.md)
