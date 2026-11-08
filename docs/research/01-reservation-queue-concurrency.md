# 리서치 01 — 예약·대기열·좌석 선점 동시성 (오픈소스/레퍼런스)

조사일: 2026-11-09. Stars/최근활동은 GitHub API(pushed_at) 기준. 라이선스 `none`은 LICENSE 파일 없음(코드 재사용 불가, 설계 참고만).
검증 범례: ✅ 직접 확인 · ⚠️ 존재만 확인 · ❓ 미확인

## 1. 풀 티켓팅 시스템

| 프로젝트 | 언어 | Stars | 최근활동 | 포폴에 참고할 포인트 | 링크 |
|---|---|---|---|---|---|
| alfio-event/alf.io ✅ | Java (Spring Boot, JDBC) | 1,630 | 2026-11 | 가장 직접적인 Java 레퍼런스. `TicketRepository`에 `select ... for update`와 `for update skip locked` 두 버전의 티켓 선점 쿼리가 나란히 있음. `TicketReservationManager.cleanupExpiredReservations()`가 만료 예약을 잠그고 정리(hold TTL 만료의 DB 버전). `WaitingQueueManager.distributeSeats()`는 waiting list에 취소 좌석을 PRE_RESERVED로 배분(기본 4h). 티켓 상태머신 FREE→PENDING→ACQUIRED / RELEASED→FREE. GPL-3.0 | https://github.com/alfio-event/alf.io — `src/main/java/alfio/repository/TicketRepository.java`, `manager/TicketReservationManager.java`, `manager/WaitingQueueManager.java` |
| pretix/pretix ✅ | Python (Django) | 2,562 | 2026-11 | `services/locking.py`: Postgres `pg_advisory_xact_lock(_shared)`로 quota 단위 락(20개 초과 시 event 단위로 승격). `services/quotas.py` `QuotaAvailability`: Redis 기반 quota 캐시(2분 stale 허용). `services/waitinglist.py` `assign_automatically`: 대기자 우선순위대로 유효기간 있는 voucher 발송. `services/cart.py`: CartPosition `expires`로 장바구니 TTL. AGPL 계열 | https://github.com/pretix/pretix — `src/pretix/base/services/{locking,quotas,waitinglist,cart,seating}.py` · https://docs.pretix.eu/en/latest/development/concepts.html |
| HiEventsDev/Hi.Events ✅ | PHP (Laravel) + React | 4,152 | 2026-11 | 활발하지만 좌석맵/대기열 없음. 도메인 모델과 React 구조 참고 정도 | https://github.com/HiEventsDev/Hi.Events |
| Attendize/Attendize ✅ | PHP | 4,283 | 2024-08 | 유지보수 중단. 제외 | https://github.com/Attendize/Attendize |
| qiurunze123/miaosha ✅ | Java (Spring Boot) | 26,565 | 2025-04 | 중국 秒杀(flash sale) 교재격. Redis 재고 선차감 → MQ 비동기 주문 → URL 난수화·수학 캡차. flash-sale 체크리스트로 훑기 | https://github.com/qiurunze123/miaosha |

## 2. 벤치마크/레퍼런스 마이크로서비스(예약 도메인)

| 프로젝트 | 언어 | Stars | 최근활동 | 포폴에 참고할 포인트 | 링크 |
|---|---|---|---|---|---|
| FudanSELab/train-ticket ✅ | Java(Spring Cloud) 외, 41 서비스 | 925 | 2025-11 | `ts-preserve-service/PreserveServiceImpl.java`가 11개 서비스를 동기 RestTemplate으로 체이닝. 분산 트랜잭션 없음, 좌석 oversell 가능 → 안티패턴 반례로 사용 | https://github.com/FudanSELab/train-ticket |
| delimitrou/DeathStarBench hotelReservation ✅ | Go (gRPC) | 964 | 2024-07 | `services/reservation/server.go` `MakeReservation`: memcached 먼저 → miss 시 MongoDB → 수량 비교. cache-aside 재고 체크가 oversell을 못 막는 반례. wrk2+Lua 워크로드 참고 | https://github.com/delimitrou/DeathStarBench/tree/master/hotelReservation |
| GoogleCloudPlatform/microservices-demo ✅ | 다언어 | 21,161 | 2026-10 | `src/loadgenerator`(Locust)와 k8s 매니페스트만 참고 | https://github.com/GoogleCloudPlatform/microservices-demo |
| spring-petclinic-microservices ✅ | Java | 2,241 | 2026-10 | Spring Cloud 보일러플레이트 참고용 | https://github.com/spring-petclinic/spring-petclinic-microservices |

## 3. 가상 대기열(Virtual Waiting Room)

| 프로젝트/자료 | 언어 | Stars | 최근활동 | 포폴에 참고할 포인트 | 링크 |
|---|---|---|---|---|---|
| aws-solutions/virtual-waiting-room-on-aws ✅ | Python (Lambda) | 118 | 2025-11, archived | 2025-11 공식 단종. `source/core-api`(대기번호·serving counter), `source/token-authorizer`(JWT), `source/sample-inlet-strategies`(주기적 N명 / 백엔드 지표 기반). "대기번호 vs serving counter → 턴 되면 JWT → API 보호" 패턴의 정석. 설계만 참고 | https://github.com/aws-solutions/virtual-waiting-room-on-aws |
| Cloudflare Waiting Room 블로그 2편 ✅ | — | — | 2021/2023 | 암호화 쿠키 티켓, DC별 카운터(Durable Object) + 전역 슬롯, 추정 오차 vs 정확 카운터 트레이드오프 | https://blog.cloudflare.com/building-waiting-room-on-workers-and-durable-objects/ · https://blog.cloudflare.com/how-waiting-room-queues/ |
| SeatGeek "Room" (QCon 2022, InfoQ) ✅ | — | — | 2022 | 실제 티켓사 대기열 공개 사례 중 가장 구체적. WebSocket 등록 시각 FIFO, visitor token → access token 교환, protected zone별 leaky-bucket exit rate, 엣지 토큰 검증, 본 서비스와 장애 격리. 대기열 설계 1순위 모델 | https://www.infoq.com/presentations/ticketing-system-virtual-waiting-room/ · 슬라이드 https://archive.qconlondon.com/system/files/presentation-slides/qcon_london_seatgeek.pdf |
| Queue-it ⚠️ | — | — | 2025 | 상용. AWS APN 블로그(Lambda@Edge 리다이렉트, 토큰 검증), SE Radio 700(2025-12) | https://aws.amazon.com/blogs/apn/how-to-manage-peak-traffic-on-aws-using-queue-its-virtual-waiting-room/ |
| zegevlier/waitingroom ✅ | Rust | 12 | 2026-06 | 분산 대기열 개인 프로젝트 | https://github.com/zegevlier/waitingroom |
| Queue-Fair/cloudflare ✅ | JS | 17 | 2026-10 | 엣지 토큰 검증 소규모 코드 | https://github.com/Queue-Fair/cloudflare |

Ticketmaster/인터파크/티켓링크/멜론/Yes24 공식 엔지니어링 글: ❓ 미발견.

## 4. 한국 GitHub 프로젝트 (대기열/콘서트 예약/선착순 쿠폰) — 전부 라이선스 없음, 설계 참고만

| 프로젝트 | 언어 | Stars | 최근활동 | 포폴에 참고할 포인트 | 링크 |
|---|---|---|---|---|---|
| mingj7235/concert ✅ | Kotlin/Spring | 13 | 2024-08 | 항해플러스 콘서트 과제 중 문서 최상. `docs/06_ConcurrencyReport.md`(낙관/비관/분산락 실험), `07_Cache_&_Redis_Queue.md`, `10_Incident_Response.md`(k6 부하·장애). Kotlin 포폴 문서 템플릿 | https://github.com/mingj7235/concert |
| renechoi/hhplus-concert-reservation-system ✅ | Java | 11 | 2026-05 | README에 항해플러스 콘서트 시나리오 요구사항 원문(대기열 토큰 → 폴링 → 좌석 1~50 → 임시배정 N분 → 결제). 요구사항 베이스라인 | https://github.com/renechoi/hhplus-concert-reservation-system |
| wlwlsus/allback-with-k8s ✅ | Java (MSA) | 42 | 2025-04 | Kafka 대기열 + k8s + Locust "티켓팅 봇" + ELK, 시연 영상 | https://github.com/wlwlsus/allback-with-k8s |
| ilgolf/ticketing-payment ✅ | Kotlin | 55 | 2025-11 | "티켓팅 상황에서 견디는 결제", 좌석 선택 시 결제대기 전환, 멀티모듈 + small/large 테스트 | https://github.com/ilgolf/ticketing-payment |
| f-lab-edu/off-coupon ✅ | Java | 60 | 2024-10 | 여기어때 네고왕 글 구조 따라 만든 선착순 쿠폰(Redis+RabbitMQ+JMeter) | https://github.com/f-lab-edu/off-coupon |
| waterfogSW/coupon-service ✅ | Kotlin | 41 | 2024-04 | 멀티모듈 헥사고날(api/worker가 도메인 공유, Redis·Kafka 어댑터 분리) | https://github.com/waterfogSW/coupon-service |
| hgs-study/redis-sorted-set-practice ✅ | Java | 27 | 2022-05 | 가장 작은 ZSET 대기열 예제 | https://github.com/hgs-study/redis-sorted-set-practice |
| KII1ua/coupon-rush ✅ | Java | 0 | 2026-10 | PR #1: 비관락 1,443 → 조건부 UPDATE 5,337 → Redis+Lua 9,087 → Redis+Kafka 8,535 req/s(개인 환경). "단계별 개선 서사" 형식 | https://github.com/KII1ua/coupon-rush/pull/1 |
| joejaeyoung/Project-coupon_rush ✅ | Java | 0 | 2026-10 | 5경로 비교, Redis 승인 쓰기 손실·순서 역전 재현 | https://github.com/joejaeyoung/Project-coupon_rush |
| geumyeongchoi/flashgate ✅ | Kotlin | 0 | 2026-09 | Redis Lua 원자 예약 + Sentinel + Resilience4j + k6 카오스(초과판매 0, p50 1.6ms) | https://github.com/geumyeongchoi/flashgate |
| TaeHyun77/ticketing-queue-backend ✅ | Kotlin | 1 | 2026-08 | SSE + StateFlow 대기열 푸시, Redis Pub/Sub 팬아웃 | https://github.com/TaeHyun77/ticketing-queue-backend |
| AnTaeho/ticketing-system ✅ | Java | 0 | 2026-11 | 회차별 정원 분할 대기열, ZSET+Lua, Redis 장애 시 fail-closed | https://github.com/AnTaeho/ticketing-system |
| heeDevUniq/Picket ✅ | Java (WebFlux) | 0 | 2026-09 | 리액티브 대기열 선택지 | https://github.com/heeDevUniq/Picket |
| boostcampwm2025/web10-beastcamp ✅ | TypeScript | 17 | 2026-03 | 부스트캠프 "리얼리즘 티켓팅 시뮬레이터"(대기열→보안문자→구역/좌석). 좌석 선택 UX | https://github.com/boostcampwm2025/web10-beastcamp |
| f-lab-clone/ticketing-backend ✅ | Kotlin | 11 | 2023-11 | Terraform/EKS/Grafana, Issue #72 대기열 설계 토론 | https://github.com/f-lab-clone/ticketing-backend |

## 5. 동시성/인프라 프리미티브

| 프로젝트 | 언어 | Stars | 최근활동 | 포폴에 참고할 포인트 | 링크 |
|---|---|---|---|---|---|
| redisson/redisson ✅ | Java | 24,422 | 2026-11 | `RLock`(watchdog), `getFairLock`, `RMultiLock`. 좌석 선점은 락보다 Lua/SET NX PX가 적합하다는 비교 근거 | https://github.com/redisson/redisson |
| redis/lettuce ✅ | Java | 5,786 | 2026-11 | Spring Boot 기본 드라이버. `EVALSHA` 캐싱, 클러스터 해시태그 `{seat:123}` | https://github.com/redis/lettuce |
| spring-data-redis ✅ | Java | 1,881 | 2026-11 | `DefaultRedisScript` + `RedisTemplate.execute(script, keys, args)`로 hold Lua 원자 실행 | https://docs.spring.io/spring-data/redis/reference/redis/scripting.html |
| lukas-krecan/ShedLock ✅ | Java | 4,234 | 2026-11 | 멀티 인스턴스에서 hold 만료/대기열 입장 스케줄러 단일 실행 | https://github.com/lukas-krecan/ShedLock |
| bucket4j/bucket4j ✅ | Java | 2,821 | 2026-11 | 토큰버킷, Redis 분산 백엔드. 대기열 exit rate, 유저별 제한 | https://github.com/bucket4j/bucket4j |
| resilience4j ✅ | Java | 10,801 | 2026-10 | Bulkhead(PG 호출 동시수), CircuitBreaker, TimeLimiter | https://github.com/resilience4j/resilience4j |
| ben-manes/caffeine ✅ | Java | 17,938 | 2026-10 | 좌석맵 L1 캐시, `refreshAfterWrite`로 stampede 완화 | https://github.com/ben-manes/caffeine |

## 6. 부하/카오스 테스트

| 프로젝트 | 언어 | Stars | 최근활동 | 포폴에 참고할 포인트 | 링크 |
|---|---|---|---|---|---|
| grafana/k6 ✅ | Go (JS 스크립트) | 32,203 | 2026-11 | `ramping-arrival-rate` executor(open model: 응답 느려져도 도착률 유지 → 오픈 스파이크 재현 필수) | https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/ |
| gatling/gatling ✅ | Scala(Kotlin DSL) | 6,959 | 2026-11 | Kotlin DSL 가능, open injection | https://github.com/gatling/gatling |
| naver/ngrinder ✅ | Java | 2,100 | 2024-10, archived | 신규 채택 비권장 | https://github.com/naver/ngrinder |
| locustio/locust ✅ | Python | 28,269 | 2026-11 | 사용자 행동 상태머신형 봇(대기→선점→결제) 작성에 편함 | https://github.com/locustio/locust |
| Shopify/toxiproxy ✅ | Go | 12,496 | 2026-10 | Redis/DB/PG 앞에서 latency/timeout 주입 | https://github.com/Shopify/toxiproxy |
| chaos-mesh ✅ | Go (k8s) | 7,971 | 2026-11 | k8s 전제. 아니면 Toxiproxy로 충분 | https://github.com/chaos-mesh/chaos-mesh |

## 7. 한국 기업 기술 블로그/발표

| 출처 | 연도 | 핵심 내용 | 검증 |
|---|---|---|---|
| 올리브영 "올영세일 선착순 쿠폰, 미발급 0%를 향한 여정" | 2025-12 | Redis GET→INCR 비원자성 + MQ 지연으로 미발급 0.014%. Lua는 정확하지만 처리량 -21%라 기각, 선차감 + 이중 카운터(-8%) 채택, 기능 플래그 점진 적용 | ✅ https://oliveyoung.tech/2025-12-15/fcfs-coupon/ |
| 우아한형제들 "빼빼로데이 선착순 1,111명" | 2016-11 | 카운터를 리플리카에서 읽어 복제 지연으로 초과 당첨 → 마스터 read, ELB warm-up | ✅ https://techblog.woowahan.com/2514/ |
| 우아한테크토크 "선착순 이벤트 서버 생존기 47만 RPM" | — | 영상, 내용 미확인 | ⚠️ https://www.youtube.com/watch?v=MTSn93rNPPE |
| 여기어때 "Redis&Kafka 선착순 쿠폰 (feat. 네고왕)" | 2022경 | Redis 중복/수량 체크, Kafka 버퍼링 | ⚠️ https://techblog.gccompany.co.kr/ |
| G마켓 "Redis Lua Script API Rate Limiter" | 2023-03 | 컨슈머 간 get-then-incr 레이스 → Lua fixed-window | ✅ https://dev.gmarket.com/69 |
| Tecoble "선착순 티켓 예매의 동시성 문제" | 2023-09 | synchronized가 @Transactional 프록시 때문에 실패하는 이유, 낙관락 vs 비관락 | ✅ https://tecoble.techcourse.co.kr/post/2023-08-16-concurrency-managing/ |
| velog "Redis Cache Stampede 해결하기 (PER)" | 2025-05 | 확률적 조기 재계산 선택 이유 | ✅ https://velog.io/@strangehoon/Redis-Cache-Stampede-문제-해결하기 |

미발견(❓): 카카오/카카오페이, 토스, 컬리, 29CM, 무신사, 인터파크/티켓링크/멜론/Yes24, LINE의 대기열·티켓팅 전용 기술 글.

## 8. 좌석맵 UI

| 프로젝트 | 언어 | Stars | 최근활동 | 포인트 | 링크 |
|---|---|---|---|---|---|
| alisaitteke/seatmap-canvas ✅ | TypeScript (d3) | 163 | 2026-11 | MIT. 블록/좌석 캔버스. React 포트는 archived | https://github.com/alisaitteke/seatmap-canvas |
| gcedo/react-seatmap ✅ | JavaScript | 46 | 2020-09 | 데이터 모델만 참고 | https://github.com/gcedo/react-seatmap |

포폴 프론트는 SVG 그리드 직접 그리는 편이 설명하기 쉬움.

## 가장 가치 높은 5개
1. alf.io — 같은 스택에서 `FOR UPDATE` vs `SKIP LOCKED`, 만료 정리 스케줄러, waiting list 재배분이 운영 코드로 존재.
2. SeatGeek Room 발표 — 실제 티켓사 대기열 설계의 유일한 1차 자료.
3. 올리브영 2025 선착순 쿠폰 글 — 트레이드오프 의사결정 기록(ADR)의 모범.
4. Cloudflare 2023 + AWS 대기열 코드 — 분산 카운터와 serving counter+JWT의 두 구현 축.
5. pretix `locking.py`/`quotas.py` — advisory lock과 stale 허용 캐시.

## 학습 순서 제안 (동시성/대기열)
1. 요구사항 고정(항해 시나리오) → Tecoble 글로 synchronized 함정 확인 → alf.io식 `FOR UPDATE` + 만료 스케줄러(ShedLock)로 DB-only v1 → k6 `ramping-arrival-rate`로 기준선.
2. `SKIP LOCKED`, advisory lock, 낙관락 3종 같은 부하로 비교. 리플리카 읽기 금지 원칙.
3. Redis `SET NX PX` + 소유 토큰 매칭 해제 Lua(hold TTL) → Redisson 버전과 비교 → 올리브영 트레이드오프 재현 ADR.
4. ZSET 최소 대기열 → visitor/access 토큰 분리, Bucket4j exit rate, ShedLock 입장 스케줄러, 폴링 vs SSE.
5. 선착순 쿠폰 사이드 기능으로 단계 비교 재현, Toxiproxy 실패 주입.
6. 읽기 경로: Caffeine L1 + Redis L2 + PER.
7. hold→pay→confirm 핸드오프: Bulkhead/TimeLimiter, hold 만료와 결제 완료 레이스.
8. train-ticket 동기 체이닝을 반례로 서사화, k6+Toxiproxy 결과 보고서.

## 주의
- 한국 개인 레포 처리량 수치는 개인 환경값. 인용 시 "참고 수치"로만.
- aws-solutions 대기열·ngrinder archived, Attendize·react-seatmap 중단.
- pretix(AGPL)/alf.io(GPL-3.0)/k6(AGPL) 코드 복사 시 라이선스 전파 주의.
