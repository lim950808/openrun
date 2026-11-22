# ADR-0008 화면 3페이지, 주간 오픈런 자동 실행, 리포트 자동 발행

- 상태: 제안
- 날짜: 2026-11-15
- 관련: [로드맵](../roadmap.md) 8주차 M4, [요구사항](../requirements.md) §2 운영 일정 · §3 M4-1~M4-5, [지표](../metrics.md) 머리말 차원, [ADR-0006](0006-waiting-queue.md) 토큰·API, [ADR-0007](0007-payment-adapter-and-fake-pg.md) 결제 API·Fake PG, [리포트 템플릿](../reports/_template.md)

## 맥락

M1~M3로 API는 대기열 → 좌석 선점 → 결제까지 이어진다. 그러나 사람이 눌러 볼 화면이 없고, 주간 오픈런은 `weekly-openrun.yml`에 자리만 있으며, 리포트는 러너 아티팩트를 사람이 내려받아 손으로 옮겨 적는다. 1차 완료 조건(블루프린트 §4)은 "공개 데모 URL + 매주 자동 발행되는 리포트 페이지"다.

제약은 둘이다. 첫째, 1차 시점에는 **상시 배포가 없다**. 벤치와 주간 오픈런은 GitHub Actions 러너 한 대 위에서 API·DB·Redis·k6를 함께 띄워 돈다(로드맵 "부하 테스트 환경 규칙"). 요구사항 §2가 가정한 "19:00 앱 스케줄러가 회차 생성 → 19:50 cron이 봇 투입 → 22:00 리포트"는 상시 서버가 있어야 성립한다. 둘째, 화면은 포트폴리오의 얼굴이지만 1순위 직군은 백엔드다. 디자인 시스템을 새로 만들지 않고 **상태별 UX(대기·만료·실패)** 가 정확한 최소 화면을 목표로 한다.

## 결정

### 1. 화면은 `apps/web` Next.js 하나, 여정 페이지 3개

| 경로 | 이름 | 하는 일 | 상태별 UX |
|---|---|---|---|
| `/showtimes/{id}/queue` | 대기열 | 간이 회원 id로 `POST /queue` 진입, 대기 토큰을 `sessionStorage`에 두고 `GET /queue/me`를 **2.5초**마다 폴링(요구사항 §1 "클라이언트 2~3초"). 앞 인원 수와 진입 시각을 보여 준다. `ADMITTED`면 입장 토큰과 만료 시각을 저장하고 좌석맵으로 이동 | 오픈 전(409 `QUEUE_NOT_OPEN` → 진입 가능 시각 카운트다운), 대기 중, 입장(자동 이동), `NOT_IN_QUEUE`·`VISITOR_TOKEN_EXPIRED`(30초 무폴링 이탈 또는 3시간 경과 → "다시 줄 서기") |
| `/showtimes/{id}/seats` | 좌석맵 | 입장 토큰(Bearer)으로 `GET /seats`, 1,500석을 SVG로 직접 그린다(VIP 10행 · R 20행 · S 20행 × 30석, 등급별 블록). 최대 2석 선택 → `POST /holds`. 201이면 결제 페이지로 이동. 좌석 상태는 10초마다, 그리고 409 뒤에 다시 읽는다 | 입장 토큰 잔여 시간 표시, 만료 10분(401 `ACCESS_TOKEN_EXPIRED` → "대기열 뒤로 재진입" 링크, M2-4), `SEAT_TAKEN`(409 → 선택 해제 후 재조회), `ACTIVE_RESERVATION_EXISTS`(409 → 저장된 예약으로 이동 링크), `INVALID_SEAT_COUNT` |
| `/reservations/{id}` | 결제·결과 | `GET /reservations/{id}`(소유자 검증, 입장 토큰 불필요 — 요구사항 §1 입장 토큰 행)로 예약과 결제 상태를 읽고 **상태가 화면을 고른다**. HOLDING이면 만료 카운트다운 + 좌석·금액 + "결제하기"(`POST /payments` → PG별 인증 단계 → `POST /confirm`). 202(CONFIRMING·UNKNOWN)면 3초 폴링 | HOLDING(결제 전), PAYING + CONFIRMING·UNKNOWN("승인 확인 중", 폴링), CONFIRMED(승인 시각·금액·좌석, 공연 전 "전액 취소"), 결제 FAILED + HOLDING("실패 사유, 다시 결제" — hold는 남아 있다), EXPIRED("선점 시간이 지났습니다" → 대기열), CANCELLED |

여정 밖의 보조 화면은 홈 `/` 하나다. 공개 회차 목록을 보여 주고 다음 회차의 대기열로 안내한다. 이를 위해 API에 공개 조회 두 개를 추가한다: `GET /api/showtimes`(오픈 예정·진행 중 회차: id · 공연명 · 공연장 · `startsAt` · `openAt` · `entryOpensAt` · 좌석 수), `GET /api/showtimes/{id}`. 둘 다 카탈로그(JPA) 읽기만 하므로 ADR-0003 규칙 안이다.

**PG 인증 단계는 설정이 고른다.** `GET /api/payments/config`의 `pg`가 `toss`면 토스 결제위젯 SDK v2 `requestPayment`(successUrl은 같은 결제 페이지, 돌아오면 `paymentKey · orderId · amount`로 confirm). `fake`면 화면 안의 "카드 결제(Fake PG)" 버튼이 `POST /api/fake-pg/payments {orderId, amount, fault: NONE}`로 인증을 흉내 낸 뒤 confirm을 부른다. 개발자용으로 fault 선택을 접힌 패널에 둔다(TIMEOUT을 골라 UNKNOWN → janitor 복구를 화면에서 볼 수 있다). 기존 정적 페이지 `static/pay/index.html`은 삭제한다(ADR-0007 §4의 "M4가 대체" 약속).

**간이 회원.** 요구사항 §1 "닉네임 + 발급 id, 로그인 없음". 화면은 첫 방문에 `memberId`를 1~1,000,000,000 범위의 난수로 발급해 `localStorage`에 두고 `X-Member-Id`로 보낸다. 봇은 2,000,000,000 이상을 쓰므로(weekly-openrun.js `MEMBER_OFFSET`) 겹치지 않는다. 닉네임은 표시용이다.

**API 호출은 같은 출처로.** Next.js `rewrites`로 `/api/*`를 `API_BASE_URL`(기본 `http://localhost:8080`)로 넘긴다. CORS 설정이 필요 없고, 토스 successUrl 리다이렉트도 같은 출처로 돌아온다. 브라우저에서 API 주소를 알 필요가 없다. 구현 메모: `output: standalone`은 rewrite 대상을 빌드 결과에 굽기 때문에 `API_BASE_URL`은 **빌드 시점** 값이다(`apps/web/Dockerfile`의 `ARG`, compose `demo`의 build arg). 배포 대상이 바뀌면 이미지를 다시 만든다.

**디자인 토큰.** 라이브러리 없이 CSS 변수로 토큰을 둔다(`--color-bg/surface/text/muted/accent/ok/warn/danger`, 좌석 상태 색 `--seat-available/held/sold/selected`, 간격·반경 단계). 라이트·다크는 `prefers-color-scheme`. 당근 SEED 토큰 구조를 이름 짓는 데만 참고하고 의존하지 않는다. 글꼴은 시스템 글꼴.

**구현 메모(2026-11-15).** Next.js 16.4(`next lint` 제거 → ESLint CLI, React 컴파일러 lint 규칙 `set-state-in-effect` 때문에 저장소 읽기는 `useSyncExternalStore`), React 19.3, Playwright 1.65, Vitest 5.1. 예약 PAYING + 결제 REQUESTED(또는 없음)는 "결제 계속하기" 화면(결제 시작은 멱등), PAYING + 결제 CONFIRMED는 예약이 따라올 때까지 "승인 확인 중"으로 본다. 좌석 조회는 등급 문자열 순(R·S·VIP)이라 블록 순서(VIP→R→S)는 화면이 정한다.

**검증.** 단위는 Vitest(좌석맵 배치 계산, 상태 → 화면 매핑, 카운트다운). 끝에서 끝은 Playwright(Chromium) 한 시나리오: 관리자 API로 지금 여는 회차 생성 → 대기열 진입 → 입장 → 좌석 2석 hold → Fake PG 결제 → CONFIRMED 화면. CI에 `web`(lint · typecheck · vitest · build)과 `web-e2e`(compose + API + Next 서버 + Playwright) 잡을 추가한다. 화면 코드는 Gradle 빌드에 들어가지 않는다.

### 2. 주간 오픈런은 러너 한 대 안에서 전 과정을 돈다

상시 배포가 없으므로 요구사항 §2의 역할 분담을 1차에 맞게 고친다(숫자는 requirements.md에서만 바꾸는 규칙에 따라 §2 표를 함께 수정한다).

| KST | 1차 구현 | 상시 배포 뒤 |
|---|---|---|
| 10:00 / 10:30 | `bench-m1`, `bench-m2` 워크플로(기존) | 그대로 |
| 19:40 | `weekly-openrun.yml` cron 시작: compose core + API(`pg=fake`, 웹훅 on, 입장 100/s) 기동, 러너 사양 기록 | 앱은 이미 떠 있음 |
| 19:45 | 워크플로가 `POST /api/admin/showtimes`로 **오늘 20:00 KST에 여는 회차**를 만든다(`startsAt` +7일). 준비가 20:00을 넘겼거나 열림이 30분 이상 남았으면(예약 지연으로 KST 날짜가 넘어간 경우) `openAt = now + 60s`로 두고 `open_at_adjusted`와 실제 시각을 결과에 적는다 | 19:00 앱 스케줄러가 생성 |
| 19:50 | 진입 허용(`entry-lead` 10분). `load/weekly-openrun.sh`가 이 시각까지 기다린 뒤 k6 `weekly-openrun.js`를 `SHOWTIME_ID` · `DURATION_S=1200` · `MEMBERS`=yaml `virtual_users`(10,000)로 시작. 시나리오 단계 19:50→20:10이 그대로 벽시계에 맞는다 | 같음 |
| 20:00 | 입장 스케줄러 가동(앱) | 같음 |
| 20:10 | 봇 도착 종료, `gracefulStop` 240초 동안 진행 중 여정 마무리 | 같음 |
| ~20:30 | 마지막 hold TTL(12분)과 결제 janitor UNKNOWN 해제(5분)가 지나도록 **k6 종료 후 15분** 대기, 그 뒤 `integrity` · `queue-integrity` · `payment-integrity` 저장 | 20:45 검증 |
| ~20:35 | §3의 리포트 생성·커밋 | 22:00 발행 |

**예약 시각은 GitHub가 보장하지 않는다.** 2026-11-14와 11-21 두 토요일 모두 세 워크플로가 예약보다 6~7시간 늦게 시작했고, 11-21 주간 오픈런은 KST 일요일 02:45에 시작해 "오늘 20:00 KST"가 17시간 뒤가 되는 바람에 열림 대기 중 잡 타임아웃으로 취소됐다([기록](../reports/runs/2026-11-21-weekly-openrun.md)). 그래서 (a) 열림 시각은 위 19:45 행처럼 30분 넘게 기다리지 않고, (b) 리포트 날짜와 같은 날 벤치 아티팩트 탐색은 실행 시각이 아니라 **그 주 토요일 슬롯**(KST 직전 토요일; 아티팩트는 그 토~일 UTC 창)을 기준으로 한다. cron은 그대로 두고 표의 시각은 "예약" 기준으로 읽는다. 러너가 AWS·Azure 어느 플릿에 배정되는지도 통제할 수 없어 리포트 머리말의 커널 접미가 플릿 표시다(ADR-0005 §8).

봇 10,000명·입장 100/s는 요구사항 §5 값 그대로 쓰고 `workflow_dispatch` 입력으로만 줄일 수 있다. 러너는 4 vCPU를 API와 k6가 나눠 쓰므로 이 실행의 p99는 운영 흐름 검증용이고 성능 수치는 벤치 3회 중앙값에서만 가져온다(로드맵 규칙). 러너 안 실행이라 "20:00에 실제로 열렸다"는 증거는 결과 JSON의 `open_at` · `k6_started_at`과 `queue_admission.admitted_at` 분포다.

### 3. 리포트는 숫자를 자동으로 채우고 서술은 사람이 쓴다

`load/report/weekly_report.py`가 `docs/reports/_template.md`의 표를 채워 `docs/reports/YYYY-MM-DD.md`와 기계용 `docs/reports/YYYY-MM-DD.json`을 쓴다.

- **입력.** 같은 날 성공한 `bench-m1` · `bench-m2` 실행의 아티팩트(`gh run download`, `actions: read`), 주간 오픈런 결과(`load/results/`), 러너 사양, `app_commit`, `scenario_hash = git hash-object load/scenarios/weekly-default.yaml`. 벤치 아티팩트가 없으면 그 표는 `n/a`로 두고 머리말에 사유를 적는다. 리포트가 안 나가는 일은 없어야 한다(로드맵 원칙 1 "기능이 덜 끝나도 리포트는 나간다").
- **규칙.** 성능 지표는 벤치 3회 중앙값(최소~최대), 정합성 카운트는 3회 합계와 주간 오픈런 값을 나눠 적는다(metrics.md). `report_no`는 `docs/reports/*.md`(템플릿 제외) 개수 + 1. "지난주" 열은 직전 `*.json`에서 읽는다.
- **사람 몫.** "한 줄 요약"은 정합성 4지표가 0인지와 hold p99 · 입장 속도로 기계가 한 문장을 만들고, "이번 주 배운 것"과 "미해결"은 `> 직접 작성` 표시를 남긴다. 숫자를 지어내지 않고, 서술을 기계가 흉내 내지도 않는다.
- **발행.** 워크플로가 `github-actions[bot]`으로 `docs(reports): 주간 오픈런 리포트 #N YYYY-MM-DD`를 `main`에 바로 커밋한다(`contents: write`). `GITHUB_TOKEN` 푸시는 다른 워크플로를 깨우지 않으므로 CI 재귀가 없다. `docs/reports/README.md`가 목록(리포트 페이지)이고 README가 링크한다. 브랜치 보호로 막히면 PR 생성으로 바꾸고 이 ADR을 고친다.
- 원시 결과(k6 요약 · 정합성 JSON · API 로그)는 러너 아티팩트 90일 보관. 레포에는 요약 JSON만 남긴다.

### 4. 배포 단위는 컨테이너 둘, 공개 URL은 사용자가 고르는 호스팅에

`apps/api/Dockerfile`(Gradle 멀티스테이지 → JRE 21)과 `apps/web/Dockerfile`(Next standalone)을 두고 compose 프로필 `demo`(postgres · redis · api · web)로 한 VM에서 뜨게 한다. 어느 호스팅에 올릴지(Fly.io · Railway · Vercel + 외부 Postgres/Redis 등)는 계정·비용이 걸린 사용자 결정이라 이 ADR은 정하지 않는다. 비밀은 호스팅의 시크릿으로만 넣는다(CLAUDE.md 규칙 7). 호스팅과 무관한 부분(이미지 발행, Caddy, 관리자 토큰, 데모 회차)은 [ADR-0009](0009-deploy-images-and-demo-host.md)가 정한다.

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 정적 HTML 3장을 Spring `static/`에 두기 | 상태별 UX와 폴링·카운트다운·SVG 좌석맵을 프레임워크 없이 짜면 코드가 더 길고, 로드맵·블루프린트가 Next.js를 명시 |
| Next.js `output: export`로 API가 정적 서빙 | 동적 경로(`/showtimes/{id}`)가 빌드 시점 목록을 요구. 같은 출처 rewrite도 못 쓴다 |
| 결제결과를 좌석맵 페이지에 합쳐 2페이지 | 토스 successUrl로 돌아오는 진입점이 따로 필요하고, 예약 링크를 공유·재방문할 수 있어야 한다 |
| API에 CORS 열고 브라우저가 API 주소를 직접 호출 | 배포마다 허용 출처를 관리해야 하고 토스 리다이렉트가 복잡해진다. rewrite 한 줄이 더 단순 |
| 주간 오픈런을 벤치와 같은 시각에 합쳐 하나의 워크플로 | 요구사항 §2가 벤치(3회, 성능)와 오픈런(1회, 운영 흐름·20:00)을 분리. 리포트 머리말 `runs`도 둘을 구분 |
| 리포트를 PR로 올려 사람이 머지 | 발행이 사람 손에 걸려 "매주 자동 발행"이 아니게 된다. 서술은 발행 뒤 커밋으로 덧붙이면 된다 |
| 리포트 서술까지 LLM이 작성 | 3차 AI 브리핑의 범위이고 평가 하네스 없이는 숫자 환각을 막을 수 없다 |
| GitHub Pages로 리포트 사이트 | 레포 설정 변경이 필요한 사용자 결정. 마크다운 커밋만으로도 GitHub이 렌더링한다. 2차 Evidence 리포트가 사이트 역할 |

## 결과

- 좋아지는 것: 대기 → 좌석 → 결제의 상태별 UX를 사람이 눌러 볼 수 있다. 토요일마다 사람이 손대지 않아도 리포트 파일이 쌓인다. 러너 한 대로 1차 완료 조건의 "자동 발행"을 만족한다.
- 감수하는 것: 공개 URL은 호스팅 결정 뒤에 생긴다. 주간 오픈런이 러너 안에서 돌아 p99는 참고용이다. 예약 시각은 GitHub cron이 보장하지 않아(관측 6~7시간 지연) "20:00에 열렸다"는 결과 JSON의 `open_at`으로만 말한다. 리포트 서술 두 절은 사람이 뒤에 채운다. Playwright가 CI 시간을 2~3분 늘린다.
- 다시 검토할 조건: 상시 배포가 생기면 §2 표의 오른쪽 열로 옮기고 회차 생성을 앱 스케줄러로 되돌린다. 화면이 4페이지 이상 필요해지면(취소·대기자 승계, 1.5차) 디자인 토큰을 패키지로 뺀다. 리포트 커밋이 브랜치 보호에 막히면 PR 방식으로.

## 증명

- Playwright `web-e2e`: 회차 생성 → 대기열 → 입장 → 2석 hold → Fake PG → CONFIRMED 화면까지 한 번에 통과(M4-1~M4-3)
- `weekly-openrun.yml` 첫 토요일 실행: `docs/reports/YYYY-MM-DD.md` 커밋이 `main`에 생기고 머리말 차원 8개가 채워져 있다(M4-4 · M4-5). 2026-11-21 첫 예약 실행은 지연으로 실패([기록](../reports/runs/2026-11-21-weekly-openrun.md)); 수정 뒤 재실행 결과는 그 기록에 적는다
- Vitest: 좌석맵 배치가 1,500석을 등급·행·번호로 빠짐없이 배치, 예약·결제 상태 조합 → 화면 상태 매핑 표
