# ADR-0010 공개 API 레이트리밋 (앱 토큰 버킷, 기본 꺼짐)

- 상태: 제안
- 날짜: 2026-11-23
- 관련: [로드맵](../roadmap.md) 1.5차 "레이트리밋 + Turnstile/ALTCHA"(이 중 레이트리밋만 앞당김), [요구사항](../requirements.md) §4 레이트리밋 행 · §5 매크로 비율 · P1-1, [지표](../metrics.md) `rate_limited_count` · `bot_block_rate`, [ADR-0009 §2·§3](0009-deploy-images-and-demo-host.md) Caddy 단일 공개 포트 · 관리자 토큰 · "알고 받는 리스크", [ADR-0006 §2](0006-waiting-queue.md) 입장 속도 Bucket4j, [ADR-0005 §8](0005-seat-inventory-row-and-strategy.md) 다시 검토할 조건

## 맥락

ADR-0009는 "공개 API에 레이트리밋이 없다"를 알고 받는 리스크로 적고, 데모 서버에 사람 트래픽이 생기면 1.5차 항목을 앞당기기로 했다. 호스팅 결정이 임박했고, 공개 URL이 열리면 그 순간부터 리스크가 현실이 된다. 한편 요구사항 §5의 매크로(폴링 0.2초, 좌석 선택 1초)는 "1.5차 봇 방어 대상"으로만 적혀 있고 막는 장치가 하나도 없다.

제약 셋. (1) 벤치와 주간 오픈런은 k6 한 프로세스, 즉 **한 IP**에서 초당 수천 요청을 보낸다. IP 기준 제한이 켜져 있으면 벤치가 스스로를 막는다. (2) 1차 회원은 로그인 없는 발급 id라 회원 기준 제한은 id를 바꾸면 피할 수 있다. 회원 기준 제한의 목적은 "같은 사람이 비정상적으로 빠르다"를 잡는 것이지 공격을 막는 것이 아니다. (3) 데모 서버는 API 컨테이너 한 대(ADR-0009 §2)라 분산 카운터가 필요 없다.

## 결정

### 1. 앱 안의 `HandlerInterceptor` + Bucket4j, 두 정책

- `RateLimitInterceptor`(apps/api)가 `/api/**`에 붙는다. Bucket4j는 이미 입장 속도 제어에 쓰는 라이브러리라(ADR-0006 §2) 스택 변경이 아니다. 버킷은 프로세스 메모리(`ConcurrentHashMap<키, Bucket>`)에 둔다. Redis에 두지 않으므로 ADR-0005 §8의 "Redis가 좌석 진실에 끼면"은 이 결정으로 발동하지 않는다.
- **정책 `ip`**: 키 = 클라이언트 IP. 대상 = `/api/**` 전체에서 `/api/admin/**`(관리자 토큰이 있다, ADR-0009 §3), `/api/webhooks/**`(토스가 보낸다, 막으면 결제가 UNKNOWN에 머문다), `/api/ping`(헬스)을 뺀 것. 한도는 요구사항 §4(초당 50, 버스트 100).
- **정책 `caller`**: 키 = `Authorization` Bearer 토큰 문자열(디코딩하지 않는다. 대기 토큰은 회원·회차별로 하나라 그 자체가 식별자다), 없으면 `X-Member-Id`. 대상 = `GET /api/showtimes/{id}/queue/me` 하나. 한도는 요구사항 §4(초당 1, 버스트 3). 사람 폴링 2.5초(0.4/s)는 통과하고 매크로 0.2초(5/s)는 약 80%가 막힌다. 키를 만들 헤더가 둘 다 없으면 이 정책은 건너뛴다(컨트롤러가 401을 준다).
- 거부는 **429**, 본문은 다른 4xx와 같은 `{"code": …}`, 코드는 `RATE_LIMITED_IP` · `RATE_LIMITED_CALLER`. `ApiErrors.respond`를 거치므로 `openrun.api.errors{code}`에 세어진다(지표 `rate_limited_count`). 헤더 `Retry-After`에 다음 토큰까지 남은 초(올림, 최소 1)를 준다. 429는 WARN으로 남기지 않는다(ApiErrors와 같은 이유: DEBUG).
- 순서: 관리자 토큰 인터셉터보다 **먼저** 돈다. 토큰이 틀린 요청도 IP 버킷을 소비한다(관리자 경로는 `ip` 대상에서 빠지므로 실제로는 겹치지 않는다).

### 2. 기본은 꺼짐. 데모 서버만 켠다

- `openrun.ratelimit.enabled`(`OPENRUN_RATELIMIT_ENABLED`, 기본 `false`). 꺼져 있으면 인터셉터는 등록조차 하지 않는다. 로컬·CI·벤치·주간 오픈런은 그대로다(제약 1). 한도는 `openrun.ratelimit.ip.*`·`openrun.ratelimit.caller.*`로 읽되 기본값은 요구사항 §4의 숫자와 같다.
- 데모 서버는 `.env`에 `OPENRUN_RATELIMIT_ENABLED=true`. compose `demo` 프로필의 api 서비스는 `server.forward-headers-strategy=framework`(`OPENRUN_FORWARD_HEADERS_STRATEGY`)로 띄워 Caddy가 붙인 `X-Forwarded-For`의 클라이언트 IP가 `remoteAddr`가 되게 한다. 로컬 기본값은 `none`: 프록시 없이 띄우는 API가 임의의 `X-Forwarded-For`를 믿으면 안 된다. Caddy는 `trusted_proxies`를 두지 않으면 클라이언트가 들고 온 `X-Forwarded-For`를 버리고 자기가 본 피어 IP만 적으므로(2.5 이후 기본), 데모 토폴로지에서 `framework`는 안전하다.
- 키 저장소 상한: 마지막 사용 10분이 지난 키는 1분마다 지우고, 키 수가 100,000을 넘으면 **전부 비운다**(fail-open: 잠시 제한이 풀릴 뿐 서비스는 산다). 토큰을 돌려 키를 늘리는 공격이 메모리를 끝없이 먹지 못하게 하는 장치고, 정확한 LRU는 1.5차 Turnstile 때 다시 본다.

### 3. 경계

- 핫패스 모듈(`domain-*`)은 건드리지 않는다. 인터셉터·설정·테스트 모두 `apps/api`. ArchUnit 규칙에 변화 없다.
- k6 시나리오는 바꾸지 않는다. 러너에서는 꺼져 있으므로 봇이 429를 볼 일이 없다. 화면(`apps/web`)의 대기열 폴링은 이미 401 외의 실패를 "다음 틱 재시도"로 다루므로 429도 그 경로를 탄다. 변경 없음.
- 숫자(초당·버스트·제외 경로)는 요구사항 §4에서만 바꾼다.

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| Caddy에서 제한(`rate_limit` 모듈) | 공식 이미지에 없어 xcaddy 커스텀 이미지가 필요하고(ADR-0009 §1 이미지 둘 원칙과 충돌), 회원·토큰 키를 알 수 없어 `caller` 정책이 불가능하다 |
| Redis 기반 Bucket4j(`bucket4j-redis`) | API가 한 대(ADR-0009 §2)라 분산 카운터가 필요 없고, 요청마다 Redis 왕복이 하나 늘어 벤치 비교 조건이 바뀐다. 다중 인스턴스가 되면 다시 본다 |
| 회원 기준 한도를 모든 API에 | 로그인이 없어 id를 바꾸면 피한다(제약 2). 폴링 하나에만 걸어 "매크로 폴링을 얼마나 막는가"라는 측정 가능한 문장을 만든다 |
| 러너에서도 켜서 매크로 차단률을 바로 리포트에 | k6가 한 IP라 `ip` 정책이 벤치를 막는다(제약 1). `caller`만 켜는 변형은 가능하지만 k6 봇이 429를 백오프하도록 시나리오를 고쳐야 하므로 1.5차 `bot_block_rate` 때 함께 한다 |
| 기본 켜짐 | 벤치·주간 오픈런 워크플로 전부에 끄는 설정을 넣어야 하고, 하나라도 빠지면 그 주 리포트 숫자가 통째로 틀어진다. 꺼짐이 안전한 기본값이다 |

## 결과

- 좋아지는 것: 데모 서버가 열리는 날 ADR-0009의 리스크 한 줄이 지워진다. 매크로 폴링을 막는 장치가 처음 생기고, 그 효과를 `openrun.api.errors{code=RATE_LIMITED_CALLER}`로 셀 수 있다.
- 감수하는 것: 같은 NAT 뒤의 여러 사람은 IP 한도를 나눠 쓴다(초당 50이면 폴링 0.4/s 기준 100명 남짓까지 여유). 메모리 버킷이라 API를 재시작하면 한도가 초기화된다. `ip` 정책은 봇 방어가 아니라 홍수 방지다.
- 다시 검토할 조건: API가 두 대 이상이 되면 Redis 버킷으로 바꾸고 ADR-0005 §8 조건을 다시 본다. 1.5차 Turnstile/ALTCHA를 넣을 때 `caller` 정책을 hold API로 넓히고 k6 봇에 429 백오프를 넣어 `bot_block_rate`를 리포트에 올린다. 데모 서버에서 사람이 429를 겪었다는 보고가 오면 요구사항 §4의 숫자를 올린다.

## 증명

- `RateLimitTest`·`RateLimitDisabledTest`·`RateLimitForwardedHeadersOffTest`(apps/api; 켜진 설정은 별도 컨텍스트, 꺼진 기본은 다른 테스트와 같은 컨텍스트): P1-1 — 한도를 넘긴 `ip` 요청이 429 `RATE_LIMITED_IP`와 `Retry-After`를 받고 카운터가 1 오른다; `/queue/me`를 버스트 넘게 부르면 `RATE_LIMITED_CALLER`, 다른 토큰은 영향 없음; `/api/admin/**`·`/api/webhooks/**`·`/api/ping`은 한도 밖; `X-Forwarded-For`는 `framework` 전략일 때만 키가 된다(`none`이면 헤더를 바꿔도 같은 버킷). 키 저장소 sweep·상한 비움은 시계 주입이 없어 테스트하지 않았다(로그 `rate_limit_keys_cleared`로 운영 중 확인).
- 기존 테스트 전부가 설정 없이(기본 꺼짐) 그대로 통과한다. 러너 벤치·주간 오픈런 수치에 변화가 없어야 한다(다음 토요일 리포트 #2로 확인).
- `docs/deploy.md`의 확인 절차에 "`.env`에 켠 뒤 한 IP에서 101번 연타하면 429" 한 줄.
