# openrun web

Next.js(App Router) 화면 3페이지와 홈. 결정과 상태별 UX는 [ADR-0008 §1](../../docs/adr/0008-web-weekly-run-and-report.md), 용어는 [glossary 화면](../../docs/glossary.md).

## 실행

API(:8080)가 먼저 떠 있어야 한다(루트 README의 compose core + `./gradlew :apps:api:bootRun`).

```bash
cd apps/web
npm ci
npm run dev            # http://localhost:3000
```

브라우저는 같은 출처의 `/api/*`만 부르고, `next.config.ts`의 rewrite가 `API_BASE_URL`로 넘긴다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `API_BASE_URL` | `http://localhost:8080` | rewrite 대상. `next build` 시점에 고정되므로 빌드할 때 준다(Dockerfile은 build arg) |
| `WEB_BASE_URL` | `http://localhost:3000` | Playwright `baseURL` |
| `API_URL` | `http://localhost:8080` | Playwright가 관리자 API로 회차를 만들 때 쓰는 주소 |

## 스크립트

| 명령 | 하는 일 |
|---|---|
| `npm run dev` / `build` / `start` | 개발 서버 / standalone 빌드 / 빌드 결과 실행 |
| `npm run lint` | ESLint(`eslint-config-next`, flat config) |
| `npm run typecheck` | `next typegen && tsc --noEmit` |
| `npm test` | Vitest: 좌석맵 배치, 예약·결제 상태 → 화면 매핑, 카운트다운 |
| `npm run e2e` | Playwright(Chromium): 회차 생성 → 대기열 → 입장 → 2석 hold → Fake PG → CONFIRMED. API(`OPENRUN_PAYMENT_PG=fake`)와 `npm run start`가 떠 있어야 한다. 처음엔 `npx playwright install chromium` |

## 페이지

| 경로 | 화면 | 주요 API |
|---|---|---|
| `/` | 회차 목록, 간이 회원 id · 닉네임 | `GET /api/showtimes` |
| `/showtimes/{id}/queue` | 대기열: 오픈 전 · 대기(2.5초 폴링) · 입장 · 이탈 | `GET /api/showtimes/{id}`, `POST /queue`, `GET /queue/me` |
| `/showtimes/{id}/seats` | SVG 좌석맵 1,500석, 최대 2석 선점(10초마다 재조회) | `GET /seats`, `POST /holds` (입장 토큰) |
| `/reservations/{id}` | 결제·결과: HOLDING · 결제 중 · 승인 확인 중 · CONFIRMED · FAILED · EXPIRED · CANCELLED | `GET /api/reservations/{id}`, `POST /payments`, `POST /api/payments/confirm`, `POST /cancel`, `DELETE` |

저장소: `localStorage` `openrun.memberId` · `openrun.nickname` · `openrun.lastReservationId`, `sessionStorage` `openrun.visitor.{showtimeId}` · `openrun.access.{showtimeId}`.

PG는 `GET /api/payments/config`의 `pg`가 고른다. `fake`면 화면 안 "카드 결제 (Fake PG)" 패널(접힌 "개발자: 고장 주입"에서 fault 선택), `toss`면 토스 결제위젯 SDK v2 `requestPayment`로 이동했다가 같은 페이지로 돌아와 confirm한다.

## 컨테이너

```bash
docker build --build-arg API_BASE_URL=http://api:8080 -t openrun-web apps/web
docker compose -f infra/compose/compose.yaml --profile demo up -d --build   # postgres · redis · api · web
```
