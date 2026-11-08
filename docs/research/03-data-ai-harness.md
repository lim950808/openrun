# 리서치 03 — 데이터 레이어(CDC/레이크하우스/리니지/시맨틱) + AI 하네스

검증일 2026-11-09. GitHub API로 언어·Stars·push·라이선스·archived 확인. **미확인** 표기는 원문 확인 실패.

## 0. 2025~2026 변동 정정 사항
- dbt-core → `dbt-labs/dbt`, v2 알파(2026-06)는 Rust 런타임. Python 1.x(v1.12) 유지. v2 알파는 피할 것.
- Prefect가 Dagster 인수 발표(2026-07). Airflow 3.x.
- **Spring AI GA 2.0.x(v2.0.2, 2026-10-23), Spring Boot 4 / Framework 7 전제.** 1.x→2.0 breaking 다수. Spring AI Alibaba는 1.1.2/Boot 3.5 고정.
- OpenAI Evals 플랫폼 2026-11-30 종료, `openai/evals` 사실상 중단. llm-guard archived(2026-07), Amundsen archived, provectus/kafka-ui → `kafbat/kafka-ui`. ragas → `vibrantlabsai/ragas`(정체).
- Kafka 4.x KRaft 전용 → 단일 컨테이너 쉬움. Flink CDC 3.5부터 PostgreSQL pipeline source, 3.4부터 Iceberg sink. Iceberg 1.11.
- **Debezium 3.2+에 OpenLineage 통합 내장** → CDC 자체가 리니지 이벤트를 Marquez에 보냄.

## 1. 스트리밍/CDC → 레이크하우스

| 프로젝트 | 언어 | Stars | 최근활동 | 라이선스 | 포인트 | 링크 |
|---|---|---|---|---|---|---|
| debezium/debezium | Java | 13.3k | 2026-11 | Apache-2.0 | Outbox Event Router SMT, OpenLineage 통합(`openlineage.integration.enabled`), `spring-integration-debezium`으로 Spring 임베딩 | https://debezium.io/documentation/reference/stable/integrations/openlineage.html |
| debezium-server (+memiiso/debezium-server-iceberg 334★) | Java | 150 | 2026-11 | Apache-2.0 | Kafka 없이 standalone. Iceberg 직결은 커뮤니티 sink | https://github.com/memiiso/debezium-server-iceberg |
| apache/kafka | Java | 34.2k | 2026-11 | Apache-2.0 | 4.x KRaft 단일 노드. 스키마 레지스트리는 Apicurio(Apache-2.0) 권장. UI `kafbat/kafka-ui` | https://github.com/Apicurio/apicurio-registry |
| redpanda | C++ | 12.7k | 2026-10 | BSL | Iceberg Topics로 Kafka Connect 없이 토픽→Iceberg. 공식 compose 랩 | https://docs.redpanda.com/labs/docker-compose/iceberg/ |
| apache/flink | Java | 26.5k | 2026-11 | Apache-2.0 | 2.2: Materialized Table. Flink SQL로 코드 없이 스트림 집계 | https://flink.apache.org/ |
| apache/flink-cdc | Java | 6.5k | 2026-11 | Apache-2.0 | YAML 파이프라인. Debezium과 역할 겹침 → 하나만 | https://nightlies.apache.org/flink/flink-cdc-docs-master/ |
| apache/iceberg | Java | 9.4k | 2026-11 | Apache-2.0 | 로컬 REST 카탈로그 `apache/iceberg-rest-fixture`(:8181) | https://iceberg.apache.org/releases/ |
| apache/paimon | Java | 3.4k | 2026-11 | Apache-2.0 | 국내 언급 거의 없음 → Iceberg 권장 | — |
| trinodb/trino | Java | 13.5k | 2026-11 | Apache-2.0 | 단일 코디네이터(힙 2~4GB), Iceberg REST 커넥터 | https://trino.io/docs/current/connector/iceberg.html |
| duckdb (+duckdb-iceberg, DuckLake) | C++ | 42.9k | 2026-11 | MIT | REST 카탈로그 attach 읽기+쓰기. DuckLake는 노트북에서 가장 가벼움. `dbt-duckdb` | https://duckdb.org/2026/05/29/new-iceberg-features |
| ClickHouse (+PeerDB) | C++ | 50.8k | 2026-11 | Apache-2.0 | 단일 컨테이너 OLAP. 배민 LLMOps도 사용 | https://github.com/PeerDB-io/peerdb |
| druid / pinot | Java | 14.1k/6.2k | 2026-11 | Apache-2.0 | 컴포넌트 많아 노트북 비추 | — |
| risingwave | Rust | 9.4k | 2026-11 | Apache-2.0 | `single_node` 단일 컨테이너, Postgres CDC 내장 → Flink 없이 실시간 MV | https://docs.risingwave.com/operate/rw-standalone-mode |
| materialize | Rust | 6.4k | 2026-11 | BSL(키 필요) | RisingWave가 편함 | — |
| polaris / nessie / lakekeeper / gravitino | — | — | 2026-11 | Apache-2.0 | REST 카탈로그 선택지. 포폴엔 rest-fixture로 충분 | — |

## 2. 오케스트레이션 & 변환

| 프로젝트 | 언어 | Stars | 최근활동 | 포인트 | 링크 |
|---|---|---|---|---|---|
| **kestra-io/kestra** | **Java** | 30.2k | 2026-11 | compose = Postgres + kestra 2개. YAML 플로우, 플러그인 Java. `plugin-dbt`, `plugin-flink`. JVM 개발자가 Python 없이 오케스트레이션 보여줄 유일한 메이저 옵션. 단 국내 공고엔 Airflow만 | https://kestra.io/docs/installation/docker-compose |
| apache/airflow | Python | 47.4k | 2026-11 | 국내 DE 공고 사실상 전부 요구. OpenLineage 프로바이더 내장 | https://github.com/apache/airflow |
| dagster | Python | 16.4k | 2026-11 | 자산 중심, dagster-dbt. Prefect 인수 불확실성 | — |
| dbt-labs/dbt | Rust/Python | 14.1k | 2026-11 | model contracts(`contract: enforced`), tests, docs/lineage DAG, `dbt-duckdb`/`dbt-trino` | https://docs.getdbt.com/docs/mesh/govern/model-contracts |
| SQLMesh | Python | 3.3k | 2026-11 | sqlglot 기반 컬럼 레벨 리니지 내장. 공고 등장 0건 | https://github.com/SQLMesh/sqlmesh |

## 3. 리니지 & 카탈로그

| 프로젝트 | 언어 | Stars | 최근활동 | 포인트 | 링크 |
|---|---|---|---|---|---|
| OpenLineage | Java | 2.8k | 2026-11 | `io.openlineage:openlineage-java:1.55.0`. Spring Boot 스타터 없음 → 직접 만든 얇은 모듈이 포폴 포인트. ColumnLineageDatasetFacet | https://openlineage.io/docs/client/java |
| Marquez | Java | 2.3k | 2026-11 | `./docker/up.sh --seed`. Debezium 3.x가 직접 이벤트 전송 → CDC→Flink→마트 리니지 자동 | https://marquezproject.ai/docs/quickstart/ · https://debezium.io/blog/2025/07/21/openlineage-debezium-flink/ |
| DataHub | Python+Java | 13.0k | 2026-11 | 퀵스타트 8GB RAM. Java emitter `io.acryl:datahub-client`. 배민은 한계 느끼고 자체 구축 | https://docs.datahub.com/docs/quickstart |
| OpenMetadata | Java+TS | 15.7k | 2026-11 | 6GiB. Java SDK `org.open-metadata:openmetadata-sdk` | https://docs.open-metadata.org/latest/sdk/java |
| apache/atlas | Java | 2.2k | 2026-10 | HBase+Solr, 비추 | — |
| amundsen | — | — | archived | 제외 | — |
| tobymao/sqlglot | Python | 9.8k | 2026-11 | `sqlglot.lineage.lineage(column, sql, schema)` → dbt SQL → 컬럼 리니지 → OpenLineage facet 변환 스크립트(Python 100줄) | https://sqlglot.com/sqlglot/lineage.html |
| reata/sqllineage | Python | 1.7k | 2026-11 | 대체 | — |

## 4. 시맨틱 레이어 / "온톨로지"

| 프로젝트 | 언어 | Stars | 최근활동 | 라이선스 | 포인트 | 링크 |
|---|---|---|---|---|---|---|
| cube-js/cube | Rust/TS | 21.2k | 2026-11 | Apache-2.0 | cubes/views/measures/dimensions, REST·GraphQL·Postgres 호환 SQL API. 드라이버 trino/duckdb/clickhouse | https://github.com/cube-js/cube |
| dbt metricflow | Python | 1.9k | 2026-11 | Apache-2.0 | 정의는 OSS, 서빙(API/MCP)은 유료 | — |
| malloy (+publisher) | TS | 2.6k | 2026-11 | MIT | publisher가 REST+MCP로 모델 서빙 → 가장 가벼운 "ontology-as-tool-surface" | https://github.com/malloydata/malloy |
| lightdash | TS | 6.2k | 2026-11 | MIT | dbt YAML에서 metrics 정의, MCP | — |
| boring-semantic-layer | Python | 488 | 2026-10 | MIT | Ibis 기반, MCP 친화 | — |
| **apache/ossie** (구 Open Semantic Interchange) | spec | 2.7k | 2026-11 | Apache-2.0 | 2026-06 Apache 인큐베이터. `ontology/` 스펙 디렉터리(성숙도 미확인). "온톨로지"를 업계 표준 어휘로 안전하게 쓸 유일한 경로 | https://github.com/apache/ossie |
| datacontract-cli / ODCS | Python / spec | 1.1k / 1.2k | 2026-10 | MIT / Apache-2.0 | `lint/test/export`, Kafka 테스트 → CI 게이트 | https://docs.datacontract.com/ |
| apache/jena | Java | 1.5k | 2026-11 | Apache-2.0 | RDF/OWL. 티켓팅 포폴엔 비관련 | — |
| NVIDIA/auto-ontology (사용자 제공) | Python+Next.js | ~10.1k | — | Apache-2.0 | DB 메타데이터 → 용어·조인·인증 SQL 지표 추출 → Postgres+pgvector 저장 → Text-to-SQL/MCP. NIM 모델 의존, 서비스 스택으로만 실행 가능(라이브러리 아님). 설계 참고, 실행은 선택 | https://github.com/NVIDIA/auto-ontology |

## 5. BI / 대시보드
Metabase(50.2k, AGPL, 단일 jar, 가장 빠름) · Superset(75.5k, Apache-2.0, compose 무거움, 공고 키워드) · Grafana(운영 지표용) · **Evidence**(7.1k, MIT, Markdown+SQL → GitHub Pages 정적 리포트 — "포폴 리포트" 자체로 적합) · Rill(3.0k, DuckDB BI-as-code) · Streamlit(Python 탐색용).

## 6. 프로덕트 분석 & 실험
PostHog(셀프호스트 공식 비지원) · Flagsmith(BSD-3, 가장 관대) / Unleash(AGPL) · **open-feature/java-sdk**(도메인 코드는 OpenFeature에만 의존 → 벤더 교체 가능) · **GrowthBook**(8.6k, MIT, 분석은 내 웨어하우스에 SELECT만 → 노출 로그를 마트에 쌓는 구조가 "실험 파이프라인" 서사에 정확히 맞음) · OpenReplay(컨테이너 20개, 제외) · Snowplow(SLULA, 설계만 차용: self-describing event, 이벤트/엔티티 분리, failed events 토픽).

직접 구현해야 가치가 생기는 것: 이벤트 택소노미(스키마 레지스트리 + DLQ), 퍼널(조회→좌석선택→대기열→결제시작→결제완료, 세션 윈도우), 코호트/리텐션(dbt contract), 결정적 해시 실험 배정 + 노출 시점 로깅, SRM 체크, 노쇼율은 상태머신 이벤트로.

## 7. AI 레이어

| 프로젝트 | 언어 | Stars | 최근활동 | 라이선스 | 포인트 | 링크 |
|---|---|---|---|---|---|---|
| spring-ai | Java | 9.6k | v2.0.2 2026-10 | Apache-2.0 | Advisors API(`SafeGuardAdvisor`, `StructuredOutputValidationAdvisor`), `@Tool`/`ToolContext`, 툴 호출 상한, MCP client/server, Micrometer→OTel(`gen_ai.*`), **Evaluation API**(`RelevancyEvaluator`, `FactCheckingEvaluator`, JUnit 예제) | https://docs.spring.io/spring-ai/reference/api/testing.html |
| spring-ai-examples | Java | 1.4k | 2026-11 | — | `agentic-patterns/{chain,parallelization,routing,orchestrator-workers,evaluator-optimizer}`, `kotlin/*` | https://github.com/spring-projects/spring-ai-examples |
| modelcontextprotocol/java-sdk | Java | 3.8k | 2026-10 | MIT | Spring AI 2.0 동봉 | — |
| spring-ai-alibaba | Java | 11.1k | 2026-03 | Apache-2.0 | Spring AI 1.1.2 고정 → 비추 | — |
| langchain4j | Java | 13.4k | 2026-10 | Apache-2.0 | Guardrails(reprompt+maxRetries) 설계만 Advisor로 이식 | https://docs.langchain4j.dev/tutorials/guardrails |
| JetBrains/koog | Kotlin | 4.7k | 2026-09 | Apache-2.0 | `agents-test`(mockLLMAnswer, mockTool) 설계 참고 | https://docs.koog.ai/testing/ |
| embabel-agent | Kotlin | 4.6k | 2026-10 | Apache-2.0 | Spring AI 위 GOAP. 블로그 비교 소재 | — |
| promptfoo | TS | 27.0k | 2026-10 | MIT | HTTP provider로 Spring `/chat` 블랙박스 회귀, GitHub Action PR 코멘트 | https://www.promptfoo.dev/docs/providers/http/ |
| deepeval | Python | 19.4k | 2026-09 | Apache-2.0 | Python 사이드 둘 때만 | — |
| inspect_ai | Python | 3.2k | 2026-11 | MIT | 연구 성향, 과함 | — |
| **langfuse** | TS | 37.1k | 2026-11 | MIT(+ee) | compose 셀프호스트. OTel ingestion `/api/public/otel/v1/traces`. 공식 Spring AI 가이드(1.0.9 기준, 2.0.x 미검증). 배민 LLMOps 채택 | https://langfuse.com/integrations/frameworks/spring-ai |
| Arize phoenix | Python | 12.2k | 2026-11 | Elastic 2.0 | Spring AI 전용 instrumentation. 2순위 | — |
| otel semantic-conventions-genai | spec | 527 | 2026-11 | Apache-2.0 | 전부 Development 상태. README 어휘로 | — |
| guardrails-ai / NeMo Guardrails | Python | 7.6k/7.5k | 2026 | Apache-2.0 | 사이드카로만. 무거움 | — |
| crystaldba/postgres-mcp | Python | 3.5k | 2026-08 | MIT | `--access-mode=restricted` → ops copilot 툴 | https://github.com/crystaldba/postgres-mcp |

## 8. 한국 컨텍스트
- AI/평가: 토스 "Toss Benchmark 구축기"(2026-09, 운영 실패 케이스를 평가셋으로 환류) https://toss.tech/article/toss-benchmark · 토스 "Skill 품질 Rubric"(2026-06) https://toss.tech/article/skill-quality-rubric · 우아한형제들 "LLMOps AI플랫폼 2.0"(2025-09, Langfuse+LiteLLM+ClickHouse+Superset) https://techblog.woowahan.com/22839/ · 우아한형제들 RAG 글(2026-03, Spring AI 언급) https://techblog.woowahan.com/25900/ · 올리브영 검색 MCP(2026-08, Kotlin) https://oliveyoung.tech/2026-08-28/search-mcp/. **Spring AI 프로덕션 도입을 명시한 국내 블로그는 미발견 → 포폴이 메울 공백.**
- 데이터: 토스 "입수는 Datalake로(Iceberg)" https://toss.tech/article/datalake-iceberg · 카카오 "Iceberg와 Flink CDC" https://tech.kakao.com/posts/656 · 카카오페이증권 Debezium CDC https://tech.kakaopay.com/post/kakaopaysec-mongodb-cdc/ · 올리브영 CDC 전환기 https://oliveyoung.tech/2025-12-29/campaign-worekr_review/ · 우아한형제들 데이터카탈로그(DataHub 한계→자체) https://techblog.woowahan.com/21353/
- 티켓팅: 국내 예매처 대기열 아키텍처 공식 글 미발견 → "공개 레퍼런스가 거의 없는 도메인을 끝까지 구현" 포지셔닝 가능.

## 노트북(16GB) 최소 데이터 스택

**프로필 A — Kafka 네이티브(공고 키워드 최대, 10~12GB)**
postgres:17(wal_level=logical) · kafka 4.x KRaft · apicurio-registry · debezium/connect(+Outbox SMT +OpenLineage SMT) · minio · iceberg-rest-fixture · flink jobmanager+taskmanager(Flink SQL 집계 → Iceberg) · trino · marquez · metabase 또는 evidence · kafbat/kafka-ui(옵션). 배치 변환 dbt-duckdb/dbt-trino + openlineage-dbt. 오케스트레이션 Kestra(또는 Airflow +2GB). compose `profiles:`로 core/lake/lineage/bi 분리 필수.

**프로필 B — 경량(Flink 제거, 6GB 이하)**
postgres → debezium-server → redpanda(Iceberg Topics) 또는 kafka · duckdb(+iceberg) 또는 DuckLake로 마트(dbt-duckdb) · risingwave single_node로 실시간 MV 1~2개 · marquez + evidence.

B→A 단계적 확장 권장. DataHub/OpenMetadata는 RAM 예산상 제외, Marquez + ODCS 컨트랙트 + dbt docs로 카탈로그 대체.

## AI 하네스 최소 구성
1. Spring Boot 4.x + Kotlin 2.x + Spring AI 2.0.2. 에이전트 3종을 `ChatClient` + Advisor 체인으로 통일.
2. 가드레일 = 커스텀 Advisor 2개(입력: PII/도메인외/인젝션, 출력: 스키마+불변식+1회 reprompt). 툴 호출 상한으로 폭주 방지.
3. 트레이싱: Actuator + micrometer-tracing-bridge-otel + otlp exporter → Langfuse 셀프호스트.
4. Eval 러너: (A) Spring AI Evaluator in JUnit 5 + 커스텀 Evaluator(툴 호출 순서, DB 상태 = outcome 채점), 골든셋 jsonl, Gradle `evalTest` 소스셋, trial 3회 pass^3, 임계치 미달 시 빌드 실패 (B) promptfoo HTTP provider 블랙박스 회귀 (C) deepeval은 Python 사이드 있을 때만.
5. CI 게이트: `test`(LLM fake) → `evalTest`(실모델, 비용 상한) → Langfuse Datasets push(선택).
6. Python 사이드(선택): sqlglot 리니지 스크립트, deepeval/ragas 노트북, Streamlit 퍼널 탐색. FDE 공고는 Python 압도적.

## ontology 포함 여부 — 솔직한 판단
- 단어를 제목/핵심 키워드로 쓰지 말 것. Palantir Ontology는 object/link + action/function + 보안·브랜칭·앱이 묶인 제품. 1인 포폴이 재현 불가.
- 국내 B2C(토스·당근·카카오·쿠팡·컬리·무신사·배민) DE/백엔드/AI 공고·블로그에 "온톨로지" 0건. 등장은 방산·제조 SI, Palantir 대체 표방 FDE 스타트업(인포시즈·스켈터랩스·인핸스), 대기업 컨설팅 담론.
- 대신 쓸 어휘(2026 공고 실제 반복): "시맨틱 레이어(비즈니스 의미 정의) → LLM이 Text-to-SQL/MCP로 안전하게 소비 → 리니지로 신뢰" — GS리테일 DE, Match Group AE(MetricFlow), LY Corp(Query Guardrail).
- 아이디어는 살릴 것: Event/Venue/Seat/Reservation/Payment/User/QueueSession 7 엔티티 + 지표(전환율·노쇼율·대기열 이탈률·점유율)를 Cube/Malloy/Rill로 선언하고 에이전트 툴로 노출 + 도메인 커맨드(취소/환불/재배정)도 툴로 = "읽기(semantic)+행동(kinetic)" 축소판. 네이밍은 "도메인 시맨틱 레이어 + 에이전트 툴 표면".
- 병원 CDW·리니지 경력은 "데이터 카탈로그/리니지/데이터 컨트랙트"로 서술.

## 채용 공고 반복 키워드 (약 30건 정성 표본)
- **DE**: Kafka·Airflow·Python·SQL·Spark·K8s·AWS — 거의 전부. 실시간(Flink), 클라우드 DW, OLAP(StarRocks·Trino) 과반. Iceberg·dbt 상승 중(우대). 2026 신규: 데이터 품질 자동점검, 지표/실험 플랫폼, 시맨틱 레이어·Text-to-SQL·MCP용 메타데이터. Debezium·DataHub·"리니지"는 공고엔 거의 없음(블로그 언어) — "CDC 실시간 파이프라인", "카탈로그·거버넌스"로 표현.
- **프로덕트/백엔드(+AI)**: "프로덕트 엔지니어" 직함은 국내 대형 B2C에서 드묾 — "AI 서비스 백엔드", "Software Engineer, Data"로 쪼개짐. Kotlin/Java+Spring **와** Python 병행 요구. Spring AI가 이름으로 등장(카카오뱅크 AI 서비스 백엔드 등). 대규모 트래픽/고가용성.
- **FDE/AI**: LLM/RAG/Agent + Python 전부(Kotlin 요구 0건). end-to-end 오너십, 빠른 PoC, Text2SQL, **평가(eval) 체계** 급증(티맵 "AI Agent Evaluation Engineer", Langfuse/Ragas/PromptFoo). Ontology/KG는 3곳.
- README/이력서 키워드 매핑: Debezium → "CDC 실시간 파이프라인", Marquez/OpenLineage → "리니지·카탈로그·거버넌스", dbt contracts+ODCS → "데이터 품질 자동점검·데이터 컨트랙트", Cube/metrics → "시맨틱 레이어·지표 정의", Spring AI Evaluator+Langfuse → "LLM 평가 체계(LLM-as-judge + 결정적 규칙, 운영 실패 환류, trace)".

## 참고 글
1. https://debezium.io/blog/2025/07/21/openlineage-debezium-flink/
2. https://debezium.io/blog/2026/05/22/what-nobody-explains-about-debezium-2026/
3. https://docs.getdbt.com/blog/dbt-core-v2-is-here
4. https://duckdb.org/2026/05/29/new-iceberg-features
5. https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents (pass@k vs pass^k)
6. https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents · https://martinfowler.com/articles/harness-engineering.html · https://openai.com/index/harness-engineering/
7. https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns · https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
8. https://techblog.woowahan.com/22839/ · https://toss.tech/article/datalake-iceberg
9. https://www.palantir.com/docs/foundry/ontology/core-concepts · https://www.snowflake.com/en/blog/apache-ossie-open-semantic-interchange-incubator

**미확인**: Flink 2.3+, Spring AI 2.0.x에서 Langfuse/Phoenix 가이드 동작, Koog starter Boot 4 호환, Cube Core MCP 포함 여부, Ossie 스펙 성숙도, 국내 공고 내 "온톨로지" 빈도(검색 색인 한계).
