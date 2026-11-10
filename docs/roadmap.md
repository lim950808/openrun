# openrun 로드맵

최종 수정: 2026-11-10. 시작일 2026-11-09(월) 기준 주차. 날짜는 목표이며 리포트 발행 주기(매주 토요일)만 고정이다.

## 한 줄 정의

매주 열리는 오픈런을 실제로 견디고, 그 전쟁을 리플레이하고 설명하는 예매 플랫폼.

## 운영 원칙

1. **리포트가 먼저다.** 3주차부터 매주 토요일 "주간 오픈런 리포트"를 발행한다. 이후 모든 개선은 리포트 숫자의 변화로 기록된다. 기능이 덜 끝나도 리포트는 나간다.
2. **주장은 테스트가 증명한다.** 단계마다 문제 재현 테스트 → 해결 → 숫자 → 한계를 남긴다. 문제 재현 테스트는 AI에 맡기지 않고 직접 쓴다.
3. **친구 레포와 겹치지 않는다.** 결제 정합성 기본기(멱등키·Outbox·멱등 컨슈머·원장)는 [payment-consistency-lab](https://github.com/jaehafe/payment-consistency-lab)을 인용하고 재사용한다. 우리 영역은 좌석 hold ↔ PG 승인창 정합, 대기열 공정성, 취소 정책, 대기자 승계, 측정 파이프라인, 평가 하네스다.
4. **합성 데이터의 한계를 숨기지 않는다.** 봇 파라미터는 공개하고, 마트는 그것을 정확히 복원하는지로 평가한다. 자세한 내용은 [ADR-0002](adr/0002-synthetic-data-limits.md).
5. **부하 수치는 고정 환경에서만.** 노트북 로컬 수치는 리포트에 쓰지 않는다. 성능 수치는 GitHub Actions 러너 또는 고정 사양 VM에서 매주 같은 조건으로 벤치마크를 3회 돌려 중앙값을 쓰고, 사양을 리포트에 명시한다. 토요일 20:00 주간 오픈런은 1회만 돌며 운영 흐름 검증용이다. 자세한 일정은 [requirements.md](requirements.md) 2절.

## 직군 트랙

1순위 백엔드·프로덕트, 데이터와 AI는 관심 영역. 따라서 1차와 1.5차를 가장 깊게 파고, 2차(데이터)와 3차(AI)는 "있고, 설명할 수 있는" 수준으로 유지한다. 데이터 또는 AI 직군 지원이 구체화되면 아래 "트랙별 확장" 항목을 켠다.

## 타임라인

| 주차 | 기간 | 마일스톤 | 산출물 | 리포트에 추가되는 지표 |
|---|---|---|---|---|
| 1 | 11/09~11/15 | **릴리스 0 하네스** | 레포 골격(Gradle 멀티모듈), Boot 4 의존성 검증, compose `core`(postgres·redis), CI(build·test·k6 스모크), CLAUDE.md, ADR 3개, 요구사항 v1, 리포트 템플릿 | 없음 |
| 2~3 | 11/16~11/29 | **M1 좌석 선점** | 락 전략 5종(`NAIVE` → `FOR_UPDATE` → `SKIP_LOCKED` → `CONDITIONAL_UPDATE` → `REDIS_LUA`) 비교. k6 `ramping-arrival-rate` 시나리오. Inventory 추상화(지정석만) | 오버셀 건수, p99, 처리량. **11/28(토) 첫 리포트 발행** |
| 4~5 | 11/30~12/13 | **M2 최소 대기열** | ZSET 순번, 입장 토큰(JWT), 입장 속도 제어(Bucket4j), 폴링 API, ShedLock 스케줄러. 봇 시나리오에 "새로고침 폭풍" 추가 | 대기열 이탈률, FIFO 위반률, 입장 지연 |
| 6~7 | 12/14~12/27 | **M3 결제 핵심** | 토스 결제위젯 + confirm, 서버 금액 검증, `Idempotency-Key`, hold TTL ↔ 10분 승인창 정합, 웹훅 inbox(서명·중복·순서 역행 폐기), 예약·결제 상태머신 분리(sealed class), Fake PG 타임아웃 주입 | hold→confirm 전환율, 승인·만료 레이스 0건, 웹훅 지연 |
| 8 | 12/28~01/03 | **최소 화면 + 자동화, 1차 완료** | Next.js 3페이지(대기열·좌석맵·결제결과), 주간 오픈런 cron, 리포트 자동 발행, README 정리 | 리포트 자동 발행 |
| 9~12 | 01/04~01/31 | **1.5차 제품 완성도** | 취소수수료 정책 + 부분취소, 대기자 승계(hold 체인), janitor 확장(보상 실패 재시도 큐·다중 인스턴스·승계 트리거), 레이트리밋 + Turnstile/ALTCHA, 1인당 동시 hold 상한, 디자인 토큰 정리, 사람 참여 "오픈런 데이" | 취소 처리 정확도, 봇 차단률, 사람 vs 봇 성공률 |
| 13~16 | 02/01~02/28 | **2차 측정 파이프라인** | Outbox → Debezium(Outbox SMT + OpenLineage) → Kafka → DuckDB·dbt 마트(퍼널·매출 손실·이탈), 봇 파라미터 복원 검증, Marquez 리니지, Evidence 리포트, GitHub Actions cron으로 dbt 실행 | 마트 복원 오차, 퍼널 단계별 전환율 |
| 17~20 | 03/01~03/28 | **3차 AI 브리핑과 평가** | Spring AI 2.0 리포트 브리핑 에이전트(마트 지표만 인용), JUnit 평가셋(pass^3), Langfuse 트레이싱, 운영 실패 케이스 환류 | 에이전트 정답률, 환각 0건 여부 |

## 트랙별 확장 (해당 직군 지원이 구체화될 때만)

- **데이터 엔지니어**: 2차에 Iceberg REST 카탈로그 + Trino 이관(리니지로 영향 분석하며 이관), Airflow 3, ODCS 데이터 컨트랙트 CI 게이트, Python sqlglot 컬럼 리니지 스크립트.
- **AI·FDE**: 3차에 MCP 툴 표면(대기열·재고·lag 조회), 지표 정의 레이어(Cube 또는 Rill), promptfoo 블랙박스 회귀, Python 평가 노트북(deepeval). NVIDIA auto-ontology는 설계 참고, 실행은 선택.
- **백엔드 심화**: 1.5차에 카카오페이 `TC0ONETIME`으로 멀티 PG 라우팅·장애 격리, Go 입장 게이트 비교 실험.

## 보류·제외

| 항목 | 결정 | 이유 |
|---|---|---|
| Kestra / Airflow | 보류 | 주간 리포트는 GitHub Actions cron이면 충분. DE 트랙에서만 Airflow |
| GrowthBook 실험 | 보류 | 봇 대상 A/B는 제품 인사이트가 아님. 사람 참여가 충분해지면 재검토 |
| Iceberg·Trino·Flink | 트랙 확장 | DE 트랙 전용. 기본 2차는 DuckDB |
| Go 메인 병행 | 제외 | 범위 2배. 입장 게이트 비교 실험만 선택 |
| 온톨로지 | 표현 변경 | "시맨틱 레이어 / 지표 정의 레이어"로 부름. 리서치 03 참고 |
| public-apis | 제외 | 합성 데이터가 실험 통제에 유리 |
| Spring Statemachine | 제외 | 2026-07 아카이브. sealed class로 직접 구현 |
| DataHub / OpenMetadata | 제외 | RAM 6~8GB. Marquez + dbt docs로 대체 |
| 식당 시간 슬롯 모델 | 1.5차 이후 선택 | Inventory 추상화의 두 번째 구현체 |

## 릴리스 0 게이트: Boot 4 의존성 검증

Spring AI 2.0이 Spring Boot 4를 전제하므로 Boot 4로 시작한다. 단 1주차 첫날에 아래를 Testcontainers 기반 스모크 테스트로 확인하고, 하나라도 막히면 Boot 3.5로 시작해 3차 직전에 올린다.

- Kotlin 2.x 플러그인(kotlin-spring, kotlin-jpa) + Boot 4 Gradle 플러그인
- Spring Data JPA + Hibernate(Boot 4 동봉 버전) + Flyway
- JdbcTemplate(핫패스), 필요 시 jOOQ 코드젠
- Spring Data Redis + Lettuce, `DefaultRedisScript`
- Testcontainers(postgres, redis), ShedLock, Bucket4j
- Debezium은 Connect 컨테이너라 Boot 버전과 무관. 2차에서 확인

## 부하 테스트 환경 규칙

- 실행 환경: GitHub Actions `ubuntu-latest` 러너(공개 레포 사양 고정) 또는 고정 사양 VM. 리포트 머리말에 vCPU·RAM·커널을 적는다.
- 벤치마크는 같은 시나리오를 3회 연속 실행(매 실행 회차 재시드), 중앙값을 보고하고 최소·최대를 함께 적는다. 주간 오픈런은 1회.
- k6 `ramping-arrival-rate`(open model)만 사용한다. 응답이 느려져도 도착률을 유지해야 오픈 스파이크가 재현된다.
- 봇 시나리오 파라미터는 `load/scenarios/*.yaml`로 레포에 공개한다.

## 참고 문헌과 라이선스 주의

코드 복사 금지, 설계만 참고: alf.io(GPL-3.0), pretix(AGPL), k6(AGPL, 스크립트 작성은 무관), Metabase(AGPL). README 참고 문헌 표에 라이선스 열을 둔다. 전체 목록은 `docs/research/01~03`.

| 단계 | 1순위 참고 | 라이선스 |
|---|---|---|
| M1 | alf.io `TicketRepository`, `TicketReservationManager` / 올리브영 2025 선착순 쿠폰 글 / Tecoble 락 비교 글 | GPL-3.0 설계만 / 글 |
| M2 | SeatGeek "Room" QCon 발표 / Cloudflare Waiting Room 2023 / AWS 대기열 코드(단종, 설계만) | 글 / 글 / Apache-2.0 |
| M3 | 토스페이먼츠 공식 문서 + tosspayments-sample / Medusa `completeCartWorkflow` / Saleor TransactionItem / Temporal `bookingsaga` | 문서 / MIT / BSD-3 / 문서 |
| 1.5차 | Stripe limited inventory 문서 / commercetools ReserveOnCart 문서 / 우아한형제들 결제 장애 대응 글 | 문서 |
| 2차 | Debezium OpenLineage 블로그 / dbt model contracts / Marquez 퀵스타트 / Evidence | Apache-2.0 / Apache-2.0 / Apache-2.0 / MIT |
| 3차 | Spring AI Evaluation API 문서 / Anthropic 에이전트 평가 글 / 토스 Benchmark 구축기 / Langfuse Spring AI 가이드 | 문서 / 글 / 글 / MIT |

## ADR 목록

- [ADR-0001 1차 범위 축소](adr/0001-first-release-scope.md)
- [ADR-0002 합성 데이터의 한계와 대응](adr/0002-synthetic-data-limits.md)
- [ADR-0003 JPA와 JDBC 혼용 경계](adr/0003-jpa-jdbc-boundary.md)
- [ADR-0004 좌석·예약·결제 상태 전이 규칙](adr/0004-state-transitions.md)

## 요구사항·용어·지표

- [requirements.md](requirements.md) 요구사항 v1 (도메인 고정값, 사용자 스토리, 비기능 목표, 봇 파라미터)
- [glossary.md](glossary.md) 용어집
- [metrics.md](metrics.md) 지표 정의
