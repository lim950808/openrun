# weekly-openrun 첫 예약 실행 기록 2026-11-21 — 실패

- 워크플로: weekly-openrun schedule, run [44931813294](https://github.com/lim950808/openrun/actions/runs/44931813294), 커밋 976dd22
- 결과: **취소(잡 타임아웃 120분)**. 리포트 커밋 없음. `docs/reports/2026-11-21.md`는 생기지 않았다
- 환경: GitHub ubuntu-latest, API·Postgres·Redis·k6 동일 러너

## 무엇이 일어났나

| UTC | KST | 단계 |
|---|---|---|
| 10:40 (예약) | 19:40 토 | cron `40 10 * * 6`. 실제로는 시작하지 않음 |
| 17:45:50 | 02:45 **일** | 잡 시작. 예약보다 7시간 5분 늦음 |
| 17:46:51 | 02:46 | `load/weekly-openrun.sh`가 회차 생성(`weekly-seats-20261121T174651Z.json` 저장). 기본 열림 시각 "오늘 20:00 KST"를 `now.astimezone(KST).replace(hour=20)`로 계산했는데 KST로는 이미 일요일이어서 **일요일 20:00 KST(17시간 뒤)** 가 됐다 |
| 17:47 ~ 19:46 | | "입장 열림 대기" 루프. API 로그에는 분당 heartbeat만 있고 k6 요청은 한 건도 없다 |
| 19:46:05 | 04:46 | `timeout-minutes: 120`으로 취소. 아티팩트는 `bootRun.log`·`runner-spec.txt`·좌석 JSON만 |

지난 토요일(11-14)도 세 워크플로가 6시간 넘게 늦게 돌았다(bench-m1 01:00 → 07:15 UTC, weekly-openrun 10:40 → 16:45 UTC). 그때는 워크플로가 이전 버전이어서 이 경로를 밟지 않았다. GitHub의 `schedule`은 예약 시각을 보장하지 않으며 이 레포에서는 두 주 연속 6~7시간 밀렸다.

## 숨어 있던 두 번째 문제

워크플로의 리포트 날짜가 `TZ=Asia/Seoul date +%F`였다. 이 실행이 끝까지 갔다면 리포트는 `2026-11-22.md`(일요일)로 발행됐을 것이다. 같은 날 벤치 아티팩트 탐색은 UTC 날짜라 이번에는 맞았지만, 지연이 13시간을 넘으면 그것도 빗나간다.

## 고친 것 (ADR-0008 §2, requirements §2)

- 열림 시각: 기본 "오늘 20:00 KST"가 지났거나 **30분 이상 남았으면** `now + 60초`로 당기고 `open_at_adjusted: true`로 기록한다. 러너가 몇 시에 시작하든 20분 안에 봇이 들어간다
- 리포트 날짜와 벤치 아티팩트 탐색 창은 실행 시각이 아니라 **그 주 토요일 슬롯**(KST 기준 직전 토요일, 아티팩트는 토~일 UTC)을 따른다
- cron은 그대로 둔다. 예약 시각은 문서에 "예약" 기준으로 적고, 실제 시작은 결과 JSON의 `open_at`·`k6_started_at`이 증거다

## 재실행 (2026-11-22 일 22:25 KST, 수동 `publish=true`, 커밋 743d400, run [45027327996](https://github.com/lim950808/openrun/actions/runs/45027327996))

- 성공. 36분(봇 20분 + 정착 15분). 리포트 #1이 `docs/reports/2026-11-21.md`(토요일 슬롯 날짜)로 커밋됐다(`aebd025`). `Resolve report date`가 일요일 실행에서 `REPORT_DATE=2026-11-21`, 벤치 창 `2026-11-21..2026-11-22`를 냈고 그 창의 bench-m1(예약 실행 44882342061)·bench-m2(44884897543) 아티팩트를 찾았다
- 열림 시각: 22:25 KST에 시작했으니 "오늘 20:00 KST"는 이미 지난 쪽이어서 `open_at_adjusted_reason: past`, `openAt = now + 60초`로 열렸다. 사건 경로(`too_far`, 02:45 시작)는 `load/test_weekly_open_at.py`로만 검증됐다. 다음 예약 실행이 다시 새벽에 돌면 그때 러너에서 확인된다
- 리포트가 끌어온 bench-m1 표는 azure 플릿의 예약 실행(생성기 포화) 값이라 `hold_p99_ms` 300.2가 목표 200을 넘는 것으로 찍혔다. 같은 날 aws 플릿 수동 실행은 116.3이었다([bench-m1 기록](2026-11-21-bench-m1.md)). 주간 리포트는 창 안의 가장 최근 성공 실행 하나만 쓰고 포화 여부(`dropped_iterations`, 벤치 러너 커널)를 표에 싣지 않는다. 2026-11-23에 `weekly_report.py`가 머리말 `bench_runner_spec`(벤치 러너 커널)과 성능 표 각주 `dropped_ratio`(요구사항 §4, 5% 넘으면 "판정 보류")를 쓰도록 고치고, 같은 아티팩트로 리포트 #1을 재생성했다(숫자 동일, 각주만 추가: `CONDITIONAL_UPDATE` 37.6%, 전략 전체 26.8~42.2%; 같은 날 aws 잡은 1.0%)
- 주간 오픈런 `admission_rate_actual` 8.4/s는 봇이 20분에 걸쳐 들어와 입장이 수요에 묶인 값이다. "설정값 ±5%" 목표는 폭풍 벤치에서만 뜻이 있다
