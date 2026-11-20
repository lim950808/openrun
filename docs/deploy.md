# 데모 서버 운영 절차

ADR-0009 §2의 런북. VPS 한 대(2 GB 메모리 기준)에 compose `demo` 프로필을 올린다. 이미지는 `publish-images.yml`이 `main`에서 `ci`가 통과한 커밋마다 GHCR에 발행하므로 서버에서는 빌드하지 않고 `pull`만 한다.

구성: Caddy(80·443, 유일한 공개 포트) → `/api/*`는 api:8080, `/actuator/*`는 404, 나머지는 web:3000. postgres·redis는 호스트 루프백에만 바인드된다.

모든 명령은 레포 루트에서 실행한다. compose는 `.env`를 compose 파일이 있는 `infra/compose/`에서 찾기 때문에 루트 `.env`를 `--env-file`로 넘긴다. 아래에서는 줄여 쓴다.

```bash
alias oc='docker compose --env-file .env -f infra/compose/compose.yaml --profile demo'
```

## 준비

1. 서버: Ubuntu 24.04 등 리눅스 x86-64(이미지는 `linux/amd64`만), 메모리 2 GB 이상. Docker Engine + compose 플러그인 설치(`docker compose version`이 v2).
2. 방화벽: 인바운드 **22, 80, 443만** 허용. 나머지는 모두 차단한다(클라우드 보안 그룹 + `ufw`). Docker가 직접 여는 포트는 Caddy의 80·443뿐이다.
3. 도메인: A 레코드를 서버 IP로. 도메인이 없으면 HTTP(`DEMO_SITE_ADDRESS=:80`)로 뜨지만 관리자 토큰이 평문으로 지나가므로(아래 "알려진 제약") 공개 운영은 도메인 + HTTPS로 한다.
4. 레포 체크아웃(compose 파일과 Caddyfile만 쓴다):

```bash
git clone https://github.com/lim950808/openrun.git && cd openrun
```

5. `.env` 작성. 예시를 복사해 값을 채우고 소유자만 읽게 한다.

```bash
cp .env.example .env
chmod 600 .env
openssl rand -hex 32   # OPENRUN_TOKEN_SECRET
openssl rand -hex 32   # OPENRUN_ADMIN_TOKEN (다른 값)
```

| 변수 | 값 |
|---|---|
| `OPENRUN_TOKEN_SECRET` | `openssl rand -hex 32` 결과(대기열 토큰 서명 키, ADR-0006 §3) |
| `OPENRUN_ADMIN_TOKEN` | `openssl rand -hex 32` 결과. `/api/admin/**` 호출에 `X-Admin-Token` 헤더로 보낸다(ADR-0009 §3) |
| `POSTGRES_PASSWORD` | 기본 `openrun`에서 바꾼다. postgres 컨테이너와 API가 같은 값을 쓴다 |
| `DEMO_SITE_ADDRESS` | 도메인(예 `openrun.example.com`). Caddy가 Let's Encrypt로 자동 HTTPS. 도메인 없이는 `:80` |
| `OPENRUN_DEMO_SHOWTIME_EVERY` | `PT15M`(15분마다 회차 생성, ADR-0009 §4) |
| `OPENRUN_PAYMENT_PG` | 토스 키를 넣기 전까지 `fake`. 토스 연동 시 `toss` + `OPENRUN_TOSS_*` 세 값 |
| `OPENRUN_IMAGE_TAG` | `latest`. 되돌릴 때만 `sha-xxxxxxx` |

토스 콘솔의 웹훅 주소는 `https://<도메인>/api/webhooks/toss`(ADR-0007 §6). Fake PG 웹훅은 compose 내부 주소를 그대로 쓴다.

## 첫 배포

```bash
oc pull
oc up -d --wait
oc ps
```

`--wait`는 api 헬스체크(`/actuator/health`, 시작 유예 20초)가 통과하고 web·caddy가 뜰 때까지 기다린다. 첫 HTTPS 요청 전에 Caddy가 인증서를 받는 데 수십 초가 걸릴 수 있다(`oc logs caddy`).

## 확인

```bash
curl -fsS -o /dev/null -w '%{http_code}\n' https://<도메인>/                     # 200 홈
curl -fsS https://<도메인>/api/showtimes                                        # 200, 회차 JSON 배열
curl -s -o /dev/null -w '%{http_code}\n' https://<도메인>/actuator/health        # 404 (Caddy가 막음)
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<도메인>/api/admin/showtimes \
  -H 'Content-Type: application/json' -d '{}'                                    # 401 (토큰 없음)
oc exec api curl -fsS localhost:8080/actuator/health                             # {"status":"UP"} 서버 안에서만
```

`OPENRUN_DEMO_SHOWTIME_EVERY=PT15M`이면 1~2분 안에 `/api/showtimes`에 다음 15분 경계에 열리는 회차가 보인다. 그 전에 손으로 만들 때:

```bash
curl -fsS -X POST https://<도메인>/api/admin/showtimes -H 'Content-Type: application/json' \
  -H "X-Admin-Token: $(grep '^OPENRUN_ADMIN_TOKEN=' .env | cut -d= -f2-)" \
  -d "{\"openAt\":\"$(date -u -d '+2 min' +%FT%TZ)\",\"startsAt\":\"$(date -u -d '+7 days' +%FT%TZ)\"}"
```

## 업데이트

`main`에 머지되고 `ci`·`publish-images`가 끝난 뒤(GitHub Actions 탭에서 확인):

```bash
git pull                 # compose.yaml·Caddyfile이 바뀌었을 수 있다
oc pull
oc up -d --wait
oc ps
```

변경된 이미지의 컨테이너만 다시 만들어진다. postgres 볼륨은 유지되고 Flyway가 기동 시 마이그레이션을 적용한다. 자동 배포(서버가 새 이미지를 스스로 당기기)는 1차에 없다(ADR-0009).

## 되돌리기

GHCR 패키지 페이지나 `publish-images` 실행 로그에서 이전 커밋의 `sha-xxxxxxx` 태그를 찾아 `.env`의 `OPENRUN_IMAGE_TAG`를 바꾸고 같은 절차를 밟는다.

```bash
sed -i 's/^OPENRUN_IMAGE_TAG=.*/OPENRUN_IMAGE_TAG=sha-xxxxxxx/' .env
oc pull && oc up -d --wait
```

api와 web이 같은 태그를 받으므로 둘이 같은 커밋으로 돌아간다. Flyway는 되돌리지 않는다: 새 마이그레이션이 이미 적용된 뒤 이전 이미지로 돌아가면 `ddl-auto=validate`가 기동을 거부할 수 있으니, 그런 경우 되돌리기보다 `main`에 fix를 올려 앞으로 간다.

## 로그·백업

```bash
oc logs -f --tail=200 api          # 애플리케이션 로그
oc logs -f --tail=200 caddy        # 접근 로그(JSON, stdout)
oc stats --no-stream               # 메모리 상한(api 1g · postgres 512m · web 256m · redis 128m · caddy 64m) 대비 사용량
oc ps                              # 상한을 넘어 재시작되면 RESTARTING/최근 재시작 횟수가 보인다
```

백업은 postgres 볼륨을 `pg_dump`로 뜬다. 복원은 빈 볼륨에 `psql`로 부어 넣는다.

```bash
oc exec postgres pg_dump -U openrun openrun > backup-$(date +%F).sql
# 복원(데이터가 비어 있는 상태에서):
oc exec -T postgres psql -U openrun openrun < backup-2026-11-29.sql
```

Caddy 인증서는 볼륨 `caddy-data`에 있다. `oc down -v`는 postgres 데이터와 인증서를 모두 지우므로 운영 중에는 `oc down`만 쓴다.

## 알려진 제약

- **HTTP로 띄우면 관리자 토큰이 평문으로 지나간다.** `DEMO_SITE_ADDRESS`가 도메인이 아니면 TLS가 없다. 그 상태에서는 `/api/admin/**`을 서버 안에서만 부른다(`oc exec api curl -H "X-Admin-Token: …" localhost:8080/api/admin/...`). 토큰이 노출됐다고 보이면 `.env`의 값을 바꾸고 `oc up -d`로 api만 다시 띄운다.
- **데모 서버의 부하 수치는 리포트에 쓰지 않는다**(CLAUDE.md 규칙 6). 주간 오픈런과 벤치는 러너에서 돈다(ADR-0008 §2). 데모 서버를 상대로 `load/*.sh`를 손으로 돌릴 때는 `BASE_URL`과 `OPENRUN_ADMIN_TOKEN`을 함께 넘기고 결과는 참고용으로만 본다.
- 공개 API에 레이트리밋이 없다(1.5차). 입장 속도 제한과 1인당 활성 예약 1건만 있다. 공격을 받으면 `oc stop caddy`로 끈다.
- 과거 회차가 15분마다 쌓인다. 홈 목록은 가장 이른 3개만 보여 주고, 데이터는 2차 리플레이 재료로 남긴다(ADR-0009 §4).
- `/actuator/*`는 Caddy가 404로 막는다. 메트릭·헬스는 서버 안에서 `oc exec api curl localhost:8080/actuator/...`로 본다.
- 이미지는 `linux/amd64`만 발행한다. arm64 서버를 쓰려면 ADR-0009 §1대로 arm64 러너 매트릭스를 먼저 추가한다.
- 로컬에서 80·443이 이미 쓰이면 `CADDY_HTTP_PORT=8088 CADDY_HTTPS_PORT=8443`로 호스트 쪽 포트만 옮긴다(사이트 주소는 `:80` 그대로). 로컬 5432·6379가 쓰이면 `POSTGRES_PORT`·`REDIS_PORT`.
