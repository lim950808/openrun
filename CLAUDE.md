# openrun 작업 규칙

매주 열리는 오픈런을 실제로 견디고, 그 전쟁을 리플레이하고 설명하는 예매 플랫폼. 포트폴리오 프로젝트이며 1순위 직군은 백엔드·프로덕트, 데이터·AI는 "있고 설명할 수 있는" 수준으로 유지한다.

## 먼저 읽을 문서

- `docs/requirements.md` 고정값(좌석 수, 가격, TTL, 입장 속도, 봇 파라미터)과 사용자 스토리 ID. 숫자는 여기서만 바꾼다.
- `docs/glossary.md` 용어와 코드 식별자. 새 이름은 여기에 먼저 추가한다.
- `docs/metrics.md` 지표명과 수식. k6 커스텀 메트릭, 검증 쿼리, dbt 모델, 리포트가 같은 이름을 쓴다.
- `docs/adr/` 결정 사항. 특히 ADR-0003(JPA·JDBC 경계), ADR-0004(상태 전이).
- `docs/roadmap.md` 주차별 범위. 현재 단계 밖의 기능은 만들지 않는다.

## 절대 규칙

1. **핫패스 테이블(`seat_hold`, `reservation`, `payment`, `payment_event_inbox`, `outbox`, `queue_*`)은 JPA로 매핑하지 않는다.** 읽기도 금지. `modules/domain-booking`, `modules/domain-payment`, `modules/outbox`에는 `jakarta.persistence`, `org.springframework.data.jpa`, `org.hibernate` 의존이 없어야 하며 `apps/api`의 ArchUnit 테스트가 이를 강제한다.
2. **스키마는 Flyway만 바꾼다.** `modules/schema/src/main/resources/db/migration/V###__name.sql`. JPA `ddl-auto`는 `validate` 고정.
3. **상태 전이는 조건부 UPDATE(`WHERE status = :from`)로 쓰고 영향 행 0이면 409.** 전이표는 ADR-0004. 새 전이를 추가하려면 ADR을 먼저 고친다.
4. **스택·라이브러리 변경은 ADR 없이는 하지 않는다.** 버전 올리기는 예외.
5. **문제 재현 테스트는 사람이 직접 쓴다.** AI는 해결 코드와 회귀 테스트를 돕는다. 재현 테스트 파일에는 `// written-by: human` 주석을 둔다.
6. **부하 수치는 고정 환경에서만 리포트에 쓴다.** 로컬 노트북 수치는 PR 설명에도 "로컬, 참고용"이라고 적는다.
7. **비밀은 레포에 넣지 않는다.** 토스 키는 `.env`(gitignore)와 GitHub Secrets만.

## 레포 구조

```
apps/api                 Spring Boot 4 · Kotlin 조립. 설정, 스케줄러, 컨트롤러, 통합 테스트
apps/web                 Next.js 화면 3페이지(대기열·좌석맵·결제결과) + 홈. npm, Vitest, Playwright. Gradle 빌드와 분리
modules/support          공통: ids, clock, 결과형 타입. Spring 의존 없음
modules/schema           Flyway 마이그레이션(리소스만)
modules/domain-catalog   JPA: venue · show · showtime · seat · member
modules/domain-booking   JDBC + Redis: seat_hold · reservation · 대기열
modules/domain-payment   JDBC: payment · payment_event_inbox · 상태머신
modules/outbox           outbox 적재
load/scenarios           봇 파라미터 yaml (공개, 복원 검증의 정답)
load/k6                  k6 스크립트
load/report              리포트 생성 스크립트(m1·m2 표, weekly_report.py 주간 리포트)
infra/compose            compose 프로필: core (postgres · redis), demo (core + caddy + api + web, GHCR 이미지 또는 로컬 빌드). Caddyfile 포함
docs/deploy.md           데모 서버 운영 절차(ADR-0009)
docs/reports             주간 리포트 아카이브. 토요일 weekly-openrun.yml이 `_template.md` 모양으로 자동 커밋, 서술 두 절은 사람이 채운다
```

## 명령

```bash
./gradlew build                                   # 전체 빌드 + 단위 + Testcontainers 통합 테스트 (Docker 필요)
./gradlew :apps:api:test --tests '*Boot4GateSmokeTest*'
./gradlew :apps:api:test --tests '*ArchitectureTest*'
docker compose -f infra/compose/compose.yaml --profile core up -d --wait
docker compose -f infra/compose/compose.yaml --profile demo up -d --build --wait   # 데모 전체(Caddy :80). 관리자 API는 OPENRUN_ADMIN_TOKEN이 비면 열림
./gradlew :apps:api:bootRun                       # localhost:8080, /api/ping, /actuator/health
OPENRUN_PAYMENT_PG=toss ./gradlew :apps:api:bootRun  # 토스 실연동(.env에 테스트 키). 결제 페이지(/reservations/{id})가 토스 위젯을 연다
(cd apps/web && npm ci && npm run dev)            # 화면 localhost:3000. npm run lint|typecheck|test|build|e2e (e2e는 API 8080 필요)
docker run --rm -i --network host grafana/k6 run - < load/k6/smoke.js   # k6 미설치 시
# 대기열: POST /api/showtimes/{id}/queue → GET /api/showtimes/{id}/queue/me (Bearer 대기 토큰) → 입장 토큰으로 좌석 API
load/bench-m2.sh                                  # M2 벤치(API 실행 중). M1 벤치는 OPENRUN_QUEUE_REQUIRE_ACCESS_TOKEN=false
OPEN_DELAY_S=90 DURATION_S=60 MEMBERS=60 SETTLE_S=30 load/weekly-openrun.sh   # 주간 오픈런 축소 실행 → python3 load/report/weekly_report.py --date ...
```

## 코드 스타일

- Kotlin 공식 스타일. 패키지 `dev.openrun.<module>`.
- 상태는 `enum`, 결과는 sealed class(`Success`·`Failure`·`Unknown`). 예외로 흐름 제어하지 않는다.
- SQL은 코드에 그대로 보이게 쓴다. 문자열 템플릿으로 SQL을 조립하지 않는다.
- 시각은 `Instant`(UTC)로 저장, 표시만 KST.
- 테스트 이름은 요구사항 ID를 앞에 붙인다. 예: `M1_1 같은 좌석 동시 요청은 한 명만 성공`.

## 커밋

- 한 커밋은 한 주제. 메시지는 `type: 요약` (feat, fix, test, docs, chore, refactor). 본문은 한국어 가능.
- 마일스톤이 끝나면 `docs/reports/`에 리포트, 필요 시 ADR 추가.
- 커밋 전 `./gradlew build` 통과를 확인한다.

## 현재 단계

M4 화면·자동화 구현 완료(ADR-0008), 1차 남은 것은 공개 배포(호스팅은 사용자 결정). 주간 리포트는 토요일마다 자동 커밋되며 서술 두 절(배운 것·미해결)은 사람이 채운다. 리포트 #1(2026-11-28 토) 전 실험 두 건을 코드로 넣었다: 실험 전략 `REDIS_LUA_OUTSIDE_TX`(ADR-0005 §7, bench-m1 `strategies` 입력으로 REDIS_LUA와 비교)와 입장 스케줄러 틱당 일괄 처리(ADR-0006 §2, `admission_tick_p99_ms`). 2026-11-17에 측정을 고쳤다: `admission_rate_actual`은 틱 기준 수식(첫·마지막 틱 제외), 순번 API는 k6 체감(`rank_api_p99_ms`)과 서버 처리(`rank_api_server_p99_ms`, `GET /api/admin/diagnostics/http-requests`)를 나눠 본다. 2026-11-20 공개 배포의 호스팅 무관 부분을 ADR-0009로 정했다(GHCR 이미지 발행 `publish-images.yml`, Caddy 단일 공개 포트, `X-Admin-Token`, 데모 회차 15분 스케줄러). 호스팅은 사용자 결정 대기. 2026-11-22 ADR-0005 기본 전략을 `CONDITIONAL_UPDATE`로 확정했다(§8; 러너 두 플릿 aws·azure에서 순위 동일, `REDIS_LUA_OUTSIDE_TX`는 지연 최저의 비교 전략으로 유지, 기본 후보 아님). GitHub 예약 실행은 두 주 연속 6~7시간 늦게 시작했고 11-21 첫 주간 오픈런은 그 때문에 실패했다: 열림 시각은 30분 넘게 기다리지 않고, 리포트 날짜·벤치 아티팩트 창은 토요일 슬롯 기준이다(ADR-0008 §2). 목표값 판정은 부하 생성기가 포화하지 않은 잡(`dropped_ratio` 5% 이하, 요구사항 §4)에서만 하고, 2026-11-23부터 주간 리포트가 벤치 러너 커널(`bench_runner_spec`)과 `dropped_ratio`를 각주로 적어 넘으면 "판정 보류"라 쓴다. 2026-11-23 공개 API 레이트리밋을 ADR-0010으로 넣었다: apps/api `RateLimitInterceptor`(Bucket4j 메모리 버킷), 정책 `ip`(IP당 50/s·버스트 100, admin·webhooks·ping 제외)와 `caller`(`GET /queue/me` 토큰당 1/s·버스트 3), 429 `RATE_LIMITED_IP`·`RATE_LIMITED_CALLER` + `Retry-After`. **기본 꺼짐**(`OPENRUN_RATELIMIT_ENABLED`), 데모 서버 `.env`만 켠다. 숫자는 요구사항 §4.
