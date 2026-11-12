# openrun

매주 열리는 오픈런을 실제로 견디고, 그 전쟁을 리플레이하고 설명하는 예매 플랫폼.

공연·식당·페스티벌의 오픈런 트래픽을 공정하게 받아내고(대기열·좌석 선점), 결제까지 정합성 있게 처리하며(토스페이먼츠 연동), 그 과정의 데이터로 매출 손실 지점을 찾아내고(CDC → 마트 → 리니지), AI 상황실이 결과를 브리핑하는(Spring AI + 평가 하네스) 포트폴리오 프로젝트.

## 상태

2026-11-12 — M1 좌석 선점(락 전략 5종, ADR-0005)과 M2 최소 대기열(ZSET 순번·입장 스케줄러·JWT 입장 토큰, ADR-0006) 구현. 첫 리포트 2026-11-28(토), 다음은 M3 결제.

## 시작하기

```bash
./gradlew build                                              # 단위 + Testcontainers 통합 테스트 (Docker 필요)
docker compose -f infra/compose/compose.yaml --profile core up -d --wait
./gradlew :apps:api:bootRun                                  # http://localhost:8080/api/ping
docker run --rm -i --network host grafana/k6 run - < load/k6/smoke.js
# 대기열: POST /api/showtimes/{id}/queue (X-Member-Id) → GET /api/showtimes/{id}/queue/me (Bearer 대기 토큰) → 입장 토큰으로 좌석 API
load/bench-m2.sh                                             # M2 새로고침 폭풍 + 주간 오픈런 봇 (API 실행 중이어야 함)
```

작업 규칙과 레포 구조는 `CLAUDE.md`.

## 문서

- `docs/blueprint.md` — **한 장으로 보는 전체 그림**: 제품 흐름, 아키텍처, 타임라인, 단계별 기능·스킬·오픈소스·결과물, 핵심 설계 다이어그램
- `docs/roadmap.md` — 주차별 로드맵, 직군 트랙, 보류·제외, Boot 4 검증 게이트
- `docs/requirements.md` — 요구사항 v1: 도메인 고정값(좌석 수·가격·TTL), 주간 운영 일정, 사용자 스토리와 수용 기준, 비기능 목표, 봇 시나리오 파라미터
- `docs/glossary.md` — 용어집: 한글·코드 식별자·정의, 쓰지 않는 말
- `docs/metrics.md` — 지표 정의: 단계별 지표의 수식·분모·측정 지점·목표
- `docs/adr/` — ADR-0001 1차 범위 축소, ADR-0002 합성 데이터 한계, ADR-0003 JPA·JDBC 경계, ADR-0004 상태 전이 규칙, ADR-0005 좌석 재고 행과 락 전략, ADR-0006 대기열
- `docs/reports/` — 주간 오픈런 리포트 아카이브 (`_template.md`)
- `docs/research/01-reservation-queue-concurrency.md` — 예약·대기열·좌석 선점 동시성 오픈소스/레퍼런스
- `docs/research/02-payment-commerce.md` — 결제·커머스: [payment-consistency-lab](https://github.com/jaehafe/payment-consistency-lab) 위에 무엇을 쌓을 것인가
- `docs/research/03-data-ai-harness.md` — CDC/레이크하우스/리니지/시맨틱 레이어 + AI 평가 하네스, 채용 키워드

## 로드맵 (초안)

| 릴리스 | 마일스톤 |
|---|---|
| 0 ✅ | 하네스: Gradle 멀티모듈, Boot 4 게이트 스모크, CLAUDE.md, ADR, CI, compose `core`, 요구사항·용어·지표 고정 |
| 1차 | M1 좌석 선점 동시성 → M2 대기열과 공정성(사람 vs 매크로) → M3 결제(hold TTL ↔ 토스 10분 승인창, 웹훅 inbox, 취소수수료, 대기자 승계) → M4 화면 → 배포 + 주간 가상 오픈런 리포트 |
| 2차 | M5 오픈런 리플레이와 데이터 마트: Debezium Outbox → Kafka → DuckDB·dbt → Iceberg·Trino, Marquez 리니지, Evidence 리포트 |
| 3차 | M6 AI 상황실과 평가 하네스: Spring AI 2.0, Langfuse, JUnit 평가셋, MCP 툴, 지표 정의 레이어 |
| 선택 | 식당 슬롯 모델, Go 입장 게이트, Flink 실시간 뷰, 카카오페이 멀티 PG, GrowthBook 실험 |

## 스택

Kotlin 2.4 · Spring Boot 4.1 · Gradle 9.7 · JDK 21 · PostgreSQL 17 · Redis 7 · Flyway · ShedLock · Bucket4j · Testcontainers 2 · ArchUnit · k6 · (2차) Kafka 4 KRaft · Debezium · DuckDB · dbt · (3차) Spring AI 2 · Langfuse · Next.js
