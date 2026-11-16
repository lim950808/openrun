# openrun

매주 열리는 오픈런을 실제로 견디고, 그 전쟁을 리플레이하고 설명하는 예매 플랫폼.

공연·식당·페스티벌의 오픈런 트래픽을 공정하게 받아내고(대기열·좌석 선점), 결제까지 정합성 있게 처리하며(토스페이먼츠 연동), 그 과정의 데이터로 매출 손실 지점을 찾아내고(CDC → 마트 → 리니지), AI 상황실이 결과를 브리핑하는(Spring AI + 평가 하네스) 포트폴리오 프로젝트.

## 상태

2026-11-15 — M1 좌석 선점(락 전략 5종, ADR-0005), M2 최소 대기열(ZSET 순번·입장 스케줄러·JWT 입장 토큰, ADR-0006), M3 결제(PG 어댑터·Fake PG 고장 주입·웹훅 인박스·결제 janitor, ADR-0007), M4 화면 3페이지·주간 오픈런 자동 실행·리포트 자동 발행(ADR-0008) 구현. 2026-11-16 리포트 #1 전 실험 두 건 추가: Redis 판정을 DB 트랜잭션 밖으로 옮긴 `REDIS_LUA_OUTSIDE_TX`(ADR-0005 §7)와 입장 스케줄러 틱당 일괄 처리(ADR-0006 §2). 매주 토요일 20:00 KST 주간 오픈런이 러너에서 돌고 `docs/reports/`에 리포트가 커밋된다. 공개 데모 URL은 호스팅 결정 뒤.

## 시작하기

```bash
./gradlew build                                              # 단위 + Testcontainers 통합 테스트 (Docker 필요)
docker compose -f infra/compose/compose.yaml --profile core up -d --wait
./gradlew :apps:api:bootRun                                  # http://localhost:8080/api/ping
docker run --rm -i --network host grafana/k6 run - < load/k6/smoke.js
(cd apps/web && npm ci && npm run dev)                       # 화면 http://localhost:3000 (API_BASE_URL 기본 localhost:8080, /api/* rewrite)
# 여정: / → /showtimes/{id}/queue (대기열) → /showtimes/{id}/seats (SVG 좌석맵) → /reservations/{id} (결제·결과, 상태별 화면)
# API만 쓰면: POST /api/showtimes/{id}/queue (X-Member-Id) → GET /queue/me (Bearer 대기 토큰) → 입장 토큰으로 좌석 API
#            → POST /api/reservations/{id}/payments → 결제 인증(토스 위젯 또는 POST /api/fake-pg/payments) → POST /api/payments/confirm
# 토스 실연동: .env.example을 .env로 복사해 테스트 키 입력, OPENRUN_PAYMENT_PG=toss로 API를 띄우면 결제 페이지가 토스 위젯을 연다
load/bench-m2.sh                                             # M2 새로고침 폭풍 + 주간 오픈런 봇(결제 포함, OPENRUN_PAYMENT_PG=fake)
OPEN_DELAY_S=90 DURATION_S=60 MEMBERS=60 SETTLE_S=30 load/weekly-openrun.sh   # 주간 오픈런 축소 실행(API 실행 중)
python3 load/report/weekly_report.py --date $(date +%F)      # 리포트 생성(토요일엔 weekly-openrun.yml이 자동 커밋)
docker compose -f infra/compose/compose.yaml --profile demo up --build   # API + 화면 컨테이너(배포 단위)
```

작업 규칙과 레포 구조는 `CLAUDE.md`.

## 문서

- `docs/blueprint.md` — **한 장으로 보는 전체 그림**: 제품 흐름, 아키텍처, 타임라인, 단계별 기능·스킬·오픈소스·결과물, 핵심 설계 다이어그램
- `docs/roadmap.md` — 주차별 로드맵, 직군 트랙, 보류·제외, Boot 4 검증 게이트
- `docs/requirements.md` — 요구사항 v1: 도메인 고정값(좌석 수·가격·TTL), 주간 운영 일정, 사용자 스토리와 수용 기준, 비기능 목표, 봇 시나리오 파라미터
- `docs/glossary.md` — 용어집: 한글·코드 식별자·정의, 쓰지 않는 말
- `docs/metrics.md` — 지표 정의: 단계별 지표의 수식·분모·측정 지점·목표
- `docs/adr/` — ADR-0001 1차 범위 축소, ADR-0002 합성 데이터 한계, ADR-0003 JPA·JDBC 경계, ADR-0004 상태 전이 규칙, ADR-0005 좌석 재고 행과 락 전략, ADR-0006 대기열, ADR-0007 결제 어댑터와 Fake PG, ADR-0008 화면·주간 오픈런 자동 실행·리포트 발행
- `docs/reports/` — 주간 오픈런 리포트 아카이브: 매주 토요일 자동 발행(`README.md` 목록, `_template.md`, 러너 실행 기록 `runs/`)
- `docs/research/01-reservation-queue-concurrency.md` — 예약·대기열·좌석 선점 동시성 오픈소스/레퍼런스
- `docs/research/02-payment-commerce.md` — 결제·커머스: [payment-consistency-lab](https://github.com/jaehafe/payment-consistency-lab) 위에 무엇을 쌓을 것인가
- `docs/research/03-data-ai-harness.md` — CDC/레이크하우스/리니지/시맨틱 레이어 + AI 평가 하네스, 채용 키워드

## 로드맵 (초안)

| 릴리스 | 마일스톤 |
|---|---|
| 0 ✅ | 하네스: Gradle 멀티모듈, Boot 4 게이트 스모크, CLAUDE.md, ADR, CI, compose `core`, 요구사항·용어·지표 고정 |
| 1차 | M1 좌석 선점 ✅ → M2 대기열 ✅ → M3 결제(hold TTL ↔ 토스 10분 승인창, 웹훅 inbox) ✅ → M4 화면 3페이지 + 주간 오픈런 자동 실행 + 리포트 자동 발행 ✅ → 공개 배포(호스팅 결정 대기). 취소수수료·대기자 승계는 1.5차 |
| 2차 | M5 오픈런 리플레이와 데이터 마트: Debezium Outbox → Kafka → DuckDB·dbt → Iceberg·Trino, Marquez 리니지, Evidence 리포트 |
| 3차 | M6 AI 상황실과 평가 하네스: Spring AI 2.0, Langfuse, JUnit 평가셋, MCP 툴, 지표 정의 레이어 |
| 선택 | 식당 슬롯 모델, Go 입장 게이트, Flink 실시간 뷰, 카카오페이 멀티 PG, GrowthBook 실험 |

## 스택

Kotlin 2.4 · Spring Boot 4.1 · Gradle 9.7 · JDK 21 · PostgreSQL 17 · Redis 7 · Flyway · ShedLock · Bucket4j · Testcontainers 2 · ArchUnit · k6 · Next.js(App Router, CSS 변수 토큰) · Playwright · GitHub Actions cron · (2차) Kafka 4 KRaft · Debezium · DuckDB · dbt · (3차) Spring AI 2 · Langfuse · Next.js
