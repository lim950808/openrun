# ADR-0005 좌석 재고 행 모델과 락 전략 선택 기준

- 상태: 제안 (기본 전략은 2026-11-28 리포트 #1의 고정 환경 수치로 확정한다)
- 날짜: 2026-11-11
- 관련: [ADR-0003](0003-jpa-jdbc-boundary.md), [ADR-0004](0004-state-transitions.md) §1, [요구사항](../requirements.md) M1-1~M1-4, [지표](../metrics.md) M1

## 맥락

M1은 락 전략 5종(`NAIVE` · `FOR_UPDATE` · `SKIP_LOCKED` · `CONDITIONAL_UPDATE` · `REDIS_LUA`)을 같은 시나리오로 비교한다. 비교가 성립하려면 다섯 전략이 **같은 테이블, 같은 검증 쿼리** 위에서 달라야 한다. "hold가 생길 때 행을 INSERT한다"는 모델은 이 조건을 만족하지 못한다. 자유 좌석에는 잠글 행이 없어 `FOR UPDATE`가 걸 곳이 없고, 유니크 제약을 두면 NAIVE가 오버셀을 재현하지 못한다.

## 결정

### 1. `seat_hold`는 회차별 좌석 재고 행이다

회차를 만들 때 공연장 좌석 수만큼(1,500행) `seat_hold(showtime_id, seat_id, price, reservation_id, member_id, expires_at)`를 미리 채운다. `reservation_id`가 null이면 비어 있는 좌석이다. 선점은 이 행을 **조건부로 차지하는 UPDATE**다.

- 비어 있음의 정의: `reservation_id is null or expires_at < now()`. 만료된 hold는 janitor가 치우기 전에도 비어 있는 것으로 본다(lazy expiry). ADR-0004 §2의 "다음 접근 시 lazy 확인"이 이것이다.
- M3에서 예약이 PAYING·CONFIRMED가 되면 `expires_at = 'infinity'`로 바꿔 만료 판정에서 빠진다. ADR-0004 §1의 "PAYING은 만료 시각을 보지 않는다"를 이 한 줄로 구현한다.
- `price`는 회차 생성 시점의 등급 가격이다. 예약 확정 금액은 `reservation_seat.price` 스냅샷으로 따로 남긴다.

### 2. 예약과 좌석의 연결은 `reservation_seat`에 따로 남긴다

오버셀은 "한 좌석에 활성 예약이 2건 이상"이다. `seat_hold.reservation_id`만 있으면 NAIVE가 덮어쓴 순간 먼저 잡은 예약과의 연결이 사라져 오버셀이 보이지 않는다. 그래서 예약이 어떤 좌석을 잡았다고 믿는지는 `reservation_seat`에 기록하고, 검증 쿼리는 이 테이블을 기준으로 센다.

```sql
select count(*) from (
  select rs.seat_id
  from reservation_seat rs join reservation r on r.id = rs.reservation_id
  where r.showtime_id = ?
    and (r.status in ('PAYING','CONFIRMED') or (r.status = 'HOLDING' and r.expires_at > now()))
  group by rs.seat_id having count(*) >= 2
) x
```

### 3. 다섯 전략은 "비어 있음 확인 → 차지" 사이를 어떻게 지키는지로만 다르다

| 전략 | 확인 | 차지 | 경합 시 |
|---|---|---|---|
| `NAIVE` | 락 없는 SELECT | 가드 없는 UPDATE | 둘 다 성공 → 오버셀 |
| `FOR_UPDATE` | `SELECT … FOR UPDATE` (seat_id 순 정렬) | UPDATE | 뒤 트랜잭션이 대기 후 "이미 찼음" |
| `SKIP_LOCKED` | `SELECT … FOR UPDATE SKIP LOCKED` | UPDATE | 잠긴 행은 건너뛰어 즉시 실패 |
| `CONDITIONAL_UPDATE` | 없음 | `UPDATE … WHERE 비어 있음 RETURNING` 한 문장 | 영향 행 수 < 요청 수 → 롤백 |
| `REDIS_LUA` | Lua `EXISTS` 전부 0 | Lua `SET … PX` 후 DB UPDATE | Lua가 0 반환 → 실패 |

- 두 좌석 요청은 전부 성공하거나 전부 실패한다. seat_id 오름차순으로 잠가 교착을 피한다.
- 회원당 활성 예약 1건은 전략과 무관하게 부분 유니크 인덱스 `reservation(showtime_id, member_id) where status in ('HOLDING','PAYING')`가 막는다.
- `REDIS_LUA`에서도 DB 행을 쓴다. 검증 쿼리를 전략과 무관하게 같게 유지하기 위해서다. Redis 키 TTL과 `expires_at`은 같은 12분이다.

### 4. 벤치마크 조건

- **hold TTL 10초, janitor 1초** (`load/scenarios/bench-m1-hold.yaml`의 `app` 블록). 운영 TTL 12분으로는 1,500석이 몇 초 만에 매진되어 벤치 120초 중 대부분이 `SEAT_TAKEN` 경로만 재게 된다. 짧은 TTL로 재고를 순환시켜 선점 경로를 지속적으로 측정한다. 리포트에는 `hold_ttl`을 머리말에 적고, 성공 처리량(`hold_throughput_rps`)과 시도 처리량(`hold_attempt_rps`)을 나란히 싣는다.
- **인기 좌석 쏠림** (`seat_choice: hot_ratio 0.8, hot_pool 50`). 요청의 80%가 VIP 앞줄 50석에 몰린다. 균등 랜덤 선택은 같은 좌석에 마이크로초 안에 겹칠 확률이 낮아 NAIVE조차 오버셀이 안 나며, 실제 오픈런과도 다르다.
- **버스트 시나리오** (`bench-m1-burst.js`). 도착률 벤치는 좌석이 "막 풀린 순간"에만 경합이 생겨 오버셀 재현이 확률적이다. 같은 좌석 하나에 100 VU가 hold→즉시 release를 반복하는 버스트를 전략마다 따로 돌려 `oversell_occurred_count`(발생)로 잰다. NAIVE는 여기서 반드시 오버셀이 나야 하고, 나머지는 0이어야 한다.
- 주간 오픈런(운영 TTL 12분)은 벤치와 별도로 1회 돌리며 정합성 지표만 비교한다.

### 5. 기본 전략 선택 기준 (리포트 #1에서 확정)

고정 환경 벤치마크 3회 중앙값으로 다음 순서로 판단한다.

1. `oversell_count`·`oversell_occurred_count`·`burst_oversell_occurred`가 0이 아닌 전략은 탈락(NAIVE).
2. `hold_p99_ms` 200ms 이하를 만족하는 전략 중 `hold_throughput_rps`가 가장 높은 것.
3. 동률이면 운영 단순성: DB만 쓰는 쪽(`CONDITIONAL_UPDATE` > `SKIP_LOCKED` > `FOR_UPDATE`)을 Redis보다 우선한다. Redis는 M2 대기열에서 어차피 쓰지만, 좌석 진실의 원천이 둘로 갈리는 비용이 있다.

임시 기본값은 `CONDITIONAL_UPDATE`다. 한 문장이고 락 대기가 없으며 친구 레포의 조건부 UPDATE와 비교하기 쉽다.

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| hold 시 행 INSERT + 유니크 제약 | NAIVE가 오버셀을 재현하지 못해 비교 실험이 성립하지 않음. `FOR UPDATE`를 걸 행이 없음 |
| `seat`에 상태 컬럼 | 회차마다 좌석 마스터를 복제해야 함. ADR-0004 §1 위반 |
| Redis만 진실의 원천 | 검증 쿼리가 전략마다 갈림. Redis 유실 시 복구 근거 없음 |
| 좌석 단위 advisory lock (pretix 방식) | 설계 참고는 하되 M1에서는 행 락과 차이가 작아 5종 안에 넣지 않음. 1.5차에서 비교 실험 후보 |

## 결과

- 좋아지는 것: 전략 교체가 `SeatClaimStrategy` 구현 하나를 바꾸는 일이 된다. 오버셀 검증 쿼리 하나로 다섯 전략을 같은 잣대로 잰다.
- 감수하는 것: 회차 생성 시 1,500행 INSERT. 만료된 hold 행이 janitor 전까지 남아 `GET seats`의 파생 상태 계산이 `expires_at`를 봐야 한다.
- 다시 검토할 조건: 1.5차 대기자 승계에서 "비어 있음" 정의에 승계 우선권이 들어오면 3절 표를 다시 쓴다.

## 증명

- `HoldConcurrencyTest`: 전략 5종 × 64스레드 동시 요청. NAIVE만 `oversell_count ≥ 1`, 나머지 0 (M1-3)
- `HoldExpiryTest`: lazy expiry와 janitor sweep (M1-2)
- 리포트 #1 전략 비교 표: `load/report/m1_report.py` 출력 (`.github/workflows/bench-m1.yml`, 토 10:00 KST)
