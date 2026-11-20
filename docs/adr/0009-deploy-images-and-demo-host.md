# ADR-0009 이미지 발행, 데모 서버 구성, 관리자 API 보호

- 상태: 제안
- 날짜: 2026-11-20
- 관련: [로드맵](../roadmap.md) 1차 "공개 배포", [블루프린트](../blueprint.md) §4 1차 완료 조건, [요구사항](../requirements.md) §2 운영 일정, [ADR-0008 §1·§4](0008-web-weekly-run-and-report.md) 같은 출처 rewrite · 컨테이너 둘, [ADR-0006 §3](0006-waiting-queue.md) 토큰 서명 키, [ADR-0007 §6](0007-payment-adapter-and-fake-pg.md) 토스 키·웹훅, CLAUDE.md 규칙 7

## 맥락

1차 완료 조건은 "공개 데모 URL + 매주 자동 발행되는 리포트 페이지"다. 리포트는 ADR-0008로 자동 발행되고, 배포 단위(컨테이너 둘, compose `demo`)도 있다. 남은 것은 (1) 이미지를 어디서 만들어 어디에 두는가, (2) 공개 URL 뒤에 무엇이 서는가, (3) 지금 인증이 없는 운영 API(`/api/admin/*`, `/api/admin/diagnostics/*`, `/actuator/*`)를 공개 서버에서 어떻게 막는가, (4) 사람이 아무 때나 들어와도 눌러볼 회차가 있는가다. 호스팅(VPS · PaaS)은 계정과 비용이 걸린 사용자 결정이라 ADR-0008 §4가 정하지 않았고 아직 정해지지 않았다. 이 ADR은 **호스팅과 무관하게 같은 네 가지**를 정하고, 호스팅이 정해지면 마지막 한 조각("VPS 한 대에 `compose up`" 또는 PaaS 매니페스트)만 남게 한다.

데모 서버는 사람이 눌러보는 용도다. 부하 수치는 러너에서만 리포트에 쓴다(CLAUDE.md 규칙 6)는 원칙은 그대로이며, 주간 오픈런과 벤치도 1차에는 러너에서 돈다(ADR-0008 §2 왼쪽 열). 데모 서버로 옮기는 것은 상시 배포가 안정된 뒤의 별도 결정이다.

## 결정

### 1. 이미지는 CI가 통과한 `main` 커밋마다 GHCR에 발행한다

- 이미지 둘: `ghcr.io/lim950808/openrun-api`, `ghcr.io/lim950808/openrun-web`. 레포가 공개이므로 패키지도 공개로 두어 데모 서버가 로그인 없이 `pull`한다.
- 워크플로 `publish-images.yml`은 `ci` 워크플로가 `main`에서 성공으로 끝났을 때(`workflow_run`) 돈다. 테스트를 통과하지 않은 커밋의 이미지는 만들지 않는다. `workflow_dispatch`도 받는다(수동 재발행).
- 태그는 `sha-<짧은 해시 7자리>`와 `latest` 둘. 데모 서버는 기본 `latest`, 되돌릴 때는 `OPENRUN_IMAGE_TAG=sha-…`. 버전 번호는 1차에 없다.
- 인증은 `GITHUB_TOKEN`(`permissions: packages: write`)만. 개인 토큰은 만들지 않는다(규칙 7).
- 플랫폼은 `linux/amd64`만. arm64(Oracle 무료 ARM 등)를 고르면 QEMU 교차 빌드 대신 arm64 러너 매트릭스를 추가한다. Gradle 빌드를 QEMU로 돌리면 10배 느리다.
- web 이미지의 `API_BASE_URL` 빌드 인자는 compose 내부 주소 `http://api:8080`으로 고정한다(ADR-0008 §1: rewrite 대상은 빌드 시점). 데모 토폴로지(§2)가 바뀌지 않는 한 이미지도 바뀌지 않는다.
- Dockerfile 둘은 ADR-0008 §4 그대로. API 이미지에 이미 `curl`이 있어 compose 헬스체크에 쓴다.

### 2. 공개 포트는 Caddy 하나, 뒤에 web과 api

compose `demo` 프로필에 `caddy` 서비스(`caddy:2-alpine`)를 추가하고 **80·443만 공개**한다. api·web·postgres·redis의 `ports`는 `demo`에서 열지 않는다(`core` 프로필의 5432·6379 노출은 로컬 개발용으로 그대로).

| 경로 | 대상 | 이유 |
|---|---|---|
| `/api/*` | `api:8080` | 폴링·좌석·결제·웹훅(`/api/webhooks/toss`)·Fake PG(`/api/fake-pg/*`)가 Next 서버를 거치지 않는다. Next rewrite는 로컬 개발(`npm run dev`)과 e2e에서만 쓰인다 |
| `/actuator/*` | 없음(404) | 공개하지 않는다. 운영자는 서버 안에서 `docker compose exec api curl localhost:8080/actuator/health` |
| 나머지 | `web:3000` | 화면. 토스 successUrl 리다이렉트는 같은 출처 `/reservations/{id}`로 돌아온다(ADR-0008 §1) |

- 사이트 주소는 환경변수 `DEMO_SITE_ADDRESS`. 로컬 기본값 `:80`(HTTP), 서버에서는 도메인(예 `openrun.example.com`)을 주면 Caddy가 Let's Encrypt로 자동 HTTPS. 인증서는 볼륨 `caddy-data`에 둔다.
- `Caddyfile`은 `infra/compose/Caddyfile`. 로그는 JSON으로 stdout.
- 이미지는 §1의 GHCR 태그를 쓴다. `image:`와 `build:`를 둘 다 두어 로컬은 `--build`로, 서버는 `pull`로 같은 파일을 쓴다.
- 메모리 상한(2 GB VM 기준): api 1 GiB(`-XX:MaxRAMPercentage=75` → 힙 최대 768 MiB), postgres 512 MiB, web 256 MiB, redis 128 MiB, caddy 64 MiB. 넘치면 컨테이너가 재시작되고 로그에 남는다. `restart: unless-stopped`.
- 비밀과 설정은 서버의 `.env`(gitignore)만: `OPENRUN_TOKEN_SECRET`, `OPENRUN_ADMIN_TOKEN`(§3), `OPENRUN_TOSS_*`, `POSTGRES_PASSWORD`, `DEMO_SITE_ADDRESS`, `OPENRUN_PAYMENT_PG`(토스 키를 넣기 전까지 `fake`). `.env.example`에 항목을 추가하고 값은 `replace_me`.
- 토스 콘솔의 웹훅 주소는 `https://<DEMO_SITE_ADDRESS>/api/webhooks/toss`. Fake PG 웹훅은 compose 내부(`http://api:8080`) 그대로.
- 운영 절차(`docs/deploy.md`): 서버 준비(Docker · 도메인 A 레코드) → `.env` → `docker compose --profile demo pull && up -d --wait` → 확인(`/`, `/api/showtimes`, 관리자 토큰 없이 `/api/admin/showtimes` 401) → 업데이트(같은 `pull && up -d`) → 되돌리기(`OPENRUN_IMAGE_TAG`) → 로그·백업(postgres 볼륨 `pg_dump`). 자동 배포(서버가 새 이미지를 스스로 당기기)는 1차에 하지 않는다.

### 3. 운영 API는 `X-Admin-Token`으로 보호한다

`/api/admin/**`(회차 생성, integrity 셋, diagnostics)은 지금 인증이 없다("No auth in 1차"). 공개 서버에서는 누구나 회차를 만들고 측정값을 초기화할 수 있으므로 앱 수준에서 막는다.

- 설정 `openrun.admin.token`(환경변수 `OPENRUN_ADMIN_TOKEN`). **값이 있으면** `/api/admin/**` 요청은 헤더 `X-Admin-Token`이 같아야 한다. 없으면 `401 ADMIN_TOKEN_REQUIRED`, 다르면 `401 ADMIN_TOKEN_INVALID`(비교는 상수 시간). 둘 다 `openrun.api.errors{code}`에 센다.
- **값이 비어 있으면** 지금처럼 열린다. 로컬 개발, CI, 벤치·주간 워크플로, 테스트는 비워 둔다. 데모 서버만 `.env`에 둔다(`openssl rand -hex 32`). 워크플로에 토큰을 넣지 않는 이유: 러너는 자기 안의 API만 부르고 외부에서 닿지 않는다.
- 구현은 `HandlerInterceptor` 하나를 `/api/admin/**`에 등록. Spring Security는 넣지 않는다(의존 추가는 ADR 대상이고, 헤더 하나 비교에 과하다. 1.5차 사람 참여에서 로그인이 생기면 그때 다시 본다).
- `/actuator/*`는 §2에서 Caddy가 공개하지 않으므로 앱에서는 건드리지 않는다.
- 화면과 봇은 `/api/admin/**`을 부르지 않는다. Playwright e2e와 bench 스크립트는 회차 생성을 관리자 API로 하므로 토큰이 비어 있는 환경에서만 돈다(지금과 같다). `load/*.sh`는 `OPENRUN_ADMIN_TOKEN`이 있으면 헤더를 붙인다(데모 서버를 상대로 손으로 돌릴 때).

### 4. 데모 서버는 15분마다 회차를 연다

사람이 아무 때나 들어와도 여정을 끝까지 눌러볼 수 있어야 한다. 요구사항 §1 "회차 1개 자동 생성"은 주간 오픈런 기준이고, ADR-0008 §2 오른쪽 열은 "상시 배포 뒤 앱 스케줄러가 19:00에 생성"이다. 데모에는 더 잦은 회차가 필요하다.

- 설정 `openrun.demo.showtime-every`(환경변수 `OPENRUN_DEMO_SHOWTIME_EVERY`, `Duration`). 기본 `PT0S` = 꺼짐. 데모 서버는 `PT15M`.
- 켜져 있으면 1분마다(ShedLock `demo-showtime`) "다음 경계 시각"(정각 기준 15분 단위, 예 20:00 · 20:15 · 20:30)에 열리는 회차가 없으면 하나 만든다. `openAt` = 다음 경계, `startsAt` = `openAt` + 7일, 좌석은 관리자 API와 같은 두 단계(JPA 회차 → JDBC `seat_hold` 1,500행, ADR-0003). 같은 `open_at`의 회차가 이미 있으면 만들지 않는다(멱등).
- 진입 허용은 `entry-lead` 10분 그대로이므로 방문자는 길어도 5분 안에 줄을 설 수 있고 그동안 화면은 카운트다운을 보여 준다(ADR-0008 §1 "오픈 전" 상태). 토요일 20:00 회차는 이 주기에 포함되므로 별도 생성이 없다.
- 과거 회차는 지우지 않는다(1차). 홈 `GET /api/showtimes`는 `startsAt > now`인 회차를 `openAt` 순으로 돌려주므로 하루 96개가 쌓여도 목록은 길어진다. 목록은 **오픈 예정·진행 중 중 가장 이른 3개**로 줄인다(화면은 "다음 회차"만 쓴다). 좌석·예약 데이터는 2차 리플레이의 재료라 남긴다.
- 숫자(15분)는 요구사항 §2에 적는다(숫자는 거기서만 바꾸는 규칙).

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 서버에서 `docker compose up --build`(이미지 발행 없음) | 2 GB VM에서 Gradle·Next 빌드가 메모리를 넘기고 10분 이상 걸린다. 테스트를 거치지 않은 트리가 그대로 뜬다 |
| Docker Hub | GHCR은 레포와 같은 계정·권한·토큰(`GITHUB_TOKEN`)으로 끝난다. 계정 하나를 더 만들 이유가 없다 |
| 멀티 아키텍처(amd64 + arm64) 이미지 | QEMU 위 Gradle 빌드가 10배 느리다. arm64 호스팅을 고르면 arm64 러너 매트릭스로 |
| Nginx + certbot | 인증서 갱신 크론과 설정 파일이 둘. Caddy는 도메인 한 줄로 자동 HTTPS |
| Traefik | Docker 라벨 기반 라우팅은 서비스가 많을 때 값어치가 있다. 라우트 세 개에 과하다 |
| Caddy에서 `/api/admin/*`만 404로 막고 앱은 그대로 | 운영자가 데모 서버의 관리자 API를 쓸 길이 SSH 터널뿐이 된다. 앱 토큰이면 어디서든 `curl -H X-Admin-Token`으로 되고 Caddy 설정이 라우팅만 남는다 |
| Spring Security + Basic 인증 | 의존 추가(ADR 대상), 필터 체인·CSRF·CORS 기본값을 전부 알아야 한다. 헤더 하나 비교에 과하다 |
| 관리자 API를 compose 내부 포트(예 8081)로 분리 | 컨트롤러 분리와 Boot 관리 포트 설정이 더 복잡하고, 외부에서 쓰려면 결국 터널 |
| 회차를 사람이 손으로 만든다 | 방문자가 "열린 회차 없음"을 볼 확률이 높다. 데모의 목적에 어긋난다 |
| 상시 회차 하나를 계속 열어 둔다(오픈 시각 과거) | 대기열·오픈 카운트다운·입장 흐름이 보이지 않는다. 좌석도 금방 동난다 |
| GitHub Pages로 화면 정적 배포 + API만 서버 | ADR-0008 §1이 `output: export`를 기각한 이유 그대로(동적 경로, 같은 출처 rewrite) |
| 서버가 새 이미지를 스스로 당기는 자동 배포(Watchtower · SSH 액션) | 1차에는 사람이 `pull && up -d` 한 줄로 충분하다. 배포 실패를 사람이 보는 편이 안전하다 |
| 공개 API 레이트리밋을 Caddy나 앱에 지금 넣기 | 1.5차 범위(레이트리밋 + Turnstile/ALTCHA). 데모 서버는 입장 속도 제한과 1인당 활성 예약 1건이 있고, 공격 받으면 꺼도 되는 서버다. 리스크로 적어 둔다 |

## 결과

- 호스팅 결정 뒤 남는 일: VPS면 `docs/deploy.md` 절차 그대로(도메인 · `.env` · `pull && up -d`), PaaS면 compose 대신 그 플랫폼의 매니페스트에 §2의 라우팅·환경변수를 옮기는 것. 어느 쪽이든 이미지·토큰·회차 스케줄러는 그대로다.
- 데모 서버가 뜨면 README의 "공개 데모 URL은 호스팅 결정 뒤"를 URL로 바꾸고, ADR-0008 §2 표의 오른쪽 열(주간 오픈런을 상시 배포에서)은 별도 결정으로 남긴다.
- 알고 받는 리스크: 공개 API에 레이트리밋이 없다(1.5차). 관리자 토큰은 헤더 하나다(TLS 뒤에서만 의미 있다; `DEMO_SITE_ADDRESS`를 도메인으로 주지 않으면 HTTP라 토큰이 노출된다 → 운영 절차에 적는다). 과거 회차가 쌓인다(2차 재료).
- 다시 검토할 조건: 데모 서버에 사람 트래픽이 생겨 레이트리밋이 필요해지면 1.5차 항목을 앞당긴다. 두 번째 환경(스테이징)이 생기면 태그를 버전으로 바꾸고 자동 배포를 본다. 주간 오픈런을 데모 서버에서 돌리게 되면 ADR-0008 §2를 고친다.

## 증명

- `publish-images.yml`이 `ci` 성공 뒤 돌고 GHCR에 `openrun-api:sha-…`·`latest`, `openrun-web:sha-…`·`latest`가 생긴다(패키지 페이지, 공개)
- `docker compose -f infra/compose/compose.yaml --profile demo config`가 통과하고, 로컬에서 `--profile demo up -d --wait` 뒤 `http://localhost/`가 홈, `http://localhost/api/showtimes`가 200, `http://localhost/actuator/health`가 404
- `OPENRUN_ADMIN_TOKEN`이 설정된 API에서 `POST /api/admin/showtimes`가 헤더 없이 401 `ADMIN_TOKEN_REQUIRED`, 틀리면 401 `ADMIN_TOKEN_INVALID`, 맞으면 201. 설정이 비면 기존 테스트 전부 그대로 통과(`AdminTokenTest`)
- `openrun.demo.showtime-every=PT15M`이면 한 틱 뒤 다음 15분 경계에 열리는 회차가 하나 있고 두 번째 틱은 만들지 않는다. `PT0S`면 아무것도 만들지 않는다. `GET /api/showtimes`는 가장 이른 3개만 돌려준다(`DemoShowtimeSchedulerTest`)
