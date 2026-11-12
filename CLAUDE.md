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
modules/support          공통: ids, clock, 결과형 타입. Spring 의존 없음
modules/schema           Flyway 마이그레이션(리소스만)
modules/domain-catalog   JPA: venue · show · showtime · seat · member
modules/domain-booking   JDBC + Redis: seat_hold · reservation · 대기열
modules/domain-payment   JDBC: payment · payment_event_inbox · 상태머신
modules/outbox           outbox 적재
load/scenarios           봇 파라미터 yaml (공개, 복원 검증의 정답)
load/k6                  k6 스크립트
infra/compose            compose 프로필: core (postgres · redis)
docs/reports             주간 리포트 아카이브. `_template.md`를 복사해 쓴다
```

## 명령

```bash
./gradlew build                                   # 전체 빌드 + 단위 + Testcontainers 통합 테스트 (Docker 필요)
./gradlew :apps:api:test --tests '*Boot4GateSmokeTest*'
./gradlew :apps:api:test --tests '*ArchitectureTest*'
docker compose -f infra/compose/compose.yaml --profile core up -d --wait
./gradlew :apps:api:bootRun                       # localhost:8080, /api/ping, /actuator/health
docker run --rm -i --network host grafana/k6 run - < load/k6/smoke.js   # k6 미설치 시
# 대기열: POST /api/showtimes/{id}/queue → GET /api/showtimes/{id}/queue/me (Bearer 대기 토큰) → 입장 토큰으로 좌석 API
load/bench-m2.sh                                  # M2 벤치(API 실행 중). M1 벤치는 OPENRUN_QUEUE_REQUIRE_ACCESS_TOKEN=false
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

M2 최소 대기열 진행 중 (ADR-0006). M1 리포트 #1은 2026-11-28(토).
