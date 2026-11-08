# openrun

매주 열리는 오픈런을 실제로 견디고, 그 전쟁을 리플레이하고 설명하는 예매 플랫폼.

공연·식당·페스티벌의 오픈런 트래픽을 공정하게 받아내고(대기열·좌석 선점), 결제까지 정합성 있게 처리하며(토스페이먼츠 연동), 그 과정의 데이터로 매출 손실 지점을 찾아내고(CDC → 마트 → 리니지), AI 상황실이 결과를 브리핑하는(Spring AI + 평가 하네스) 포트폴리오 프로젝트.

## 상태

2026-11-09 — 리서치 완료, 구현 시작 전.

## 문서

- `docs/research/01-reservation-queue-concurrency.md` — 예약·대기열·좌석 선점 동시성 오픈소스/레퍼런스
- `docs/research/02-payment-commerce.md` — 결제·커머스: [payment-consistency-lab](https://github.com/jaehafe/payment-consistency-lab) 위에 무엇을 쌓을 것인가
- `docs/research/03-data-ai-harness.md` — CDC/레이크하우스/리니지/시맨틱 레이어 + AI 평가 하네스, 채용 키워드

## 로드맵 (초안)

| 릴리스 | 마일스톤 |
|---|---|
| 0 | 하네스: 레포 골격, CLAUDE.md, ADR 템플릿, CI, compose 프로필, 요구사항 고정 |
| 1차 | M1 좌석 선점 동시성 → M2 대기열과 공정성(사람 vs 매크로) → M3 결제(hold TTL ↔ 토스 10분 승인창, 웹훅 inbox, 취소수수료, 대기자 승계) → M4 화면 → 배포 + 주간 가상 오픈런 리포트 |
| 2차 | M5 오픈런 리플레이와 데이터 마트: Debezium Outbox → Kafka → DuckDB·dbt → Iceberg·Trino, Marquez 리니지, Evidence 리포트 |
| 3차 | M6 AI 상황실과 평가 하네스: Spring AI 2.0, Langfuse, JUnit 평가셋, MCP 툴, 지표 정의 레이어 |
| 선택 | 식당 슬롯 모델, Go 입장 게이트, Flink 실시간 뷰, 카카오페이 멀티 PG, GrowthBook 실험 |

## 스택 (초안)

Kotlin 2 · Spring Boot 4 · PostgreSQL 17 · Redis · Kafka 4 (KRaft) · Debezium · 토스페이먼츠 · k6 · Toxiproxy · Testcontainers · OpenTelemetry · Next.js
