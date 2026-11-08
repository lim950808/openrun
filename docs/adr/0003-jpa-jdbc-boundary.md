# ADR-0003 JPA와 JDBC 혼용 경계

- 상태: 승인
- 날짜: 2026-11-09
- 관련: [로드맵](../roadmap.md) 릴리스 0·M1·M3, 리서치 01

## 맥락

핫패스(좌석 선점, hold, 결제 상태 전이)는 `SELECT ... FOR UPDATE SKIP LOCKED`, 조건부 UPDATE, 배치 UPSERT처럼 SQL이 코드에 그대로 드러나야 한다. 영속성 컨텍스트와 쓰기 지연(flush)은 동시성 실험의 관측을 흐린다. 친구 레포가 JdbcTemplate만 쓴 이유도 같다. 반면 국내 B2C 면접에서 JPA는 필수 질문이라 완전 배제는 손해다.

## 결정

도메인을 두 부류로 나누고 각각 다른 접근 방식을 쓴다.

**JPA(Spring Data JPA + Hibernate)**: 공연, 회차, 공연장·좌석 배치도 마스터, 회원, 주최자, 정책 테이블. 변경 빈도가 낮고 관계 탐색이 많은 참조 데이터.

**JDBC(JdbcTemplate, 필요 시 jOOQ)**: `seat_hold`, `reservation`, `payment`, `payment_event_inbox`, `outbox`, `queue_*`. 동시성이 핵심인 쓰기 경로와 멱등 처리.

**경계 규칙**
1. 핫패스 테이블은 JPA 엔티티로 매핑하지 않는다. 읽기 전용 조회라도 금지한다. 같은 테이블을 두 방식이 건드리는 순간 flush 타이밍과 1차 캐시 때문에 "JDBC가 쓴 값을 JPA가 못 보는" 버그가 생긴다.
2. 핫패스 유스케이스의 트랜잭션 안에서는 JPA 쓰기를 하지 않는다. JPA 읽기는 허용하되, 그 결과를 JDBC 쓰기의 입력으로만 쓴다.
3. 모듈을 분리한다. `domain-catalog`(JPA), `domain-booking`(JDBC), `domain-payment`(JDBC). 경계 위반은 ArchUnit 테스트로 막는다.
4. 시작은 JdbcTemplate이다. 친구 레포와 코드 비교가 쉽고 의존성이 적다. 쿼리가 동적 조건으로 복잡해지면 jOOQ 도입을 별도 ADR로 결정한다.
5. 마이그레이션은 Flyway 단일 소스. JPA `ddl-auto`는 `validate`만 허용한다.

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 전부 JPA | 핫패스 SQL이 숨고, `@Lock`과 네이티브 쿼리 혼용이 오히려 복잡. 동시성 실험 관측이 흐려짐 |
| 전부 JdbcTemplate(친구 레포 방식) | 면접 JPA 질문 대응 근거가 사라짐. 참조 데이터의 관계 탐색을 손으로 짜는 비용 |
| 전부 jOOQ | 타입 안전은 좋으나 코드젠 설정이 릴리스 0을 무겁게 함. 필요해지면 도입 |
| Exposed(Kotlin) | 국내 채용 시장에서 질문되지 않음 |

## 결과

- 좋아지는 것: 핫패스 SQL이 PR에서 그대로 보인다. "왜 여기는 JPA를 안 썼나"가 면접에서 설명 가능한 설계 결정이 된다.
- 감수하는 것: 두 가지 접근 방식의 테스트 픽스처와 트랜잭션 설정을 따로 관리한다. 경계 규칙을 어기면 디버깅이 어려우므로 ArchUnit으로 강제한다.
- 다시 검토할 조건: 핫패스 쿼리가 동적 조건 5개 이상으로 복잡해지면 jOOQ ADR을 연다.

## 증명

- ArchUnit 테스트: `domain-booking`, `domain-payment`에 `jakarta.persistence` import 없음
- M1 리포트: 각 락 전략의 SQL이 코드 링크로 첨부됨
- Boot 4 검증 스모크: JPA 모듈과 JDBC 모듈이 같은 Testcontainers Postgres에서 동작
