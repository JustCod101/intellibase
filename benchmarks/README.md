# IntelliBase Benchmarks

本目录只记录可复现的性能测试脚本、原始结果和测试前提。任何 README/简历中的性能数字都必须能追溯到这里。

## 目录

| 路径 | 说明 |
|---|---|
| `scripts/generate-100k-pgvector-fixtures.sql` | PostgreSQL 内生成 10 万条 `document_chunk` + 1536 维向量测试数据 |
| `scripts/seed-chat-benchmark.sql` | 为 SSE 端到端压测准备租户、用户、知识库、会话和检索分块 |
| `scripts/generate-benchmark-jwt.mjs` | 生成与 benchmark fixture 匹配的 JWT（避免依赖登录接口造数） |
| `scripts/mock-openai-server.mjs` | 本地 OpenAI-compatible mock：embeddings / streaming chat / rerank，用于链路冒烟与压测脚本调通 |
| `scripts/pgvector-index-benchmark.sql` | HNSW / IVFFlat 参数对比 SQL（构建时间、查询延迟、召回候选） |
| `scripts/pgvector-latency-percentiles.sql` | 多查询 HNSW/GIN P50/P95/P99 延迟采样 SQL |
| `scripts/k6-chat-stream.js` | SSE `/api/v1/chat/stream` 端到端压测脚本 |
| `raw-results/` | 保存每次压测原始输出，禁止只贴结论 |

## 前置条件

- JDK 17
- Docker / Docker Compose
- PostgreSQL 16 + pgvector（`docker-compose.yml` 已提供）
- k6（HTTP/SSE 端到端压测）；本机未安装时可用 `grafana/k6` Docker 镜像
- 可用的 OpenAI-compatible LLM/Embedding API（真实端到端 RAG 压测需要）

> `mock-openai-server.mjs` 只用于验证“Spring Boot → Embedding → Hybrid Retrieval → Rerank → Streaming LLM → SSE”的链路和压测脚本，不得把 mock 结果写成真实模型延迟或回答质量。

## 1. 启动基础设施

```bash
docker compose up -d postgres redis rabbitmq minio
```

## 2. 生成 10 万向量数据

```bash
psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v fixture_kb_id=90001 \
  -v fixture_rows=100000 \
  -f benchmarks/scripts/generate-100k-pgvector-fixtures.sql
```

## 3. pgvector 索引参数对比

```bash
psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v fixture_kb_id=90001 \
  -f benchmarks/scripts/pgvector-index-benchmark.sql \
  | tee benchmarks/raw-results/pgvector-$(date +%Y%m%d-%H%M%S).txt
```

## 4. pgvector 多查询分位数

```bash
psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v fixture_kb_id=90001 \
  -v sample_runs=200 \
  -f benchmarks/scripts/pgvector-latency-percentiles.sql \
  | tee benchmarks/raw-results/pgvector-latency-$(date +%Y%m%d-%H%M%S).txt
```

## 5. k6 端到端压测

### 5.1 准备端到端 fixture

```bash
psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v benchmark_rows=5000 \
  -f benchmarks/scripts/seed-chat-benchmark.sql
```

默认 fixture：

- `tenant_id=91001`
- `user_id=91001`
- `kb_id=91001`
- `conversation_id=91001`

生成 JWT：

```bash
AUTH_TOKEN=$(node benchmarks/scripts/generate-benchmark-jwt.mjs)
```

### 5.2 使用本地 mock API 调通链路（非真实性能 claim）

启动 mock OpenAI-compatible API：

```bash
node benchmarks/scripts/mock-openai-server.mjs
```

启动 IntelliBase（示例覆盖 LLM/Embedding/Rerank 到 mock 服务）：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) \
OPENAI_BASE_URL=http://127.0.0.1:18080/v1 \
RAG_RERANK_EXTERNAL_ENABLED=true \
RAG_RERANK_API_URL=http://127.0.0.1:18080/v1/rerank \
mvn -pl intellibase-server spring-boot:run
```

运行 k6（本机已安装）：

```bash
BASE_URL=http://localhost:8080 \
AUTH_TOKEN=<jwt> \
CONVERSATION_ID=91001 \
k6 run benchmarks/scripts/k6-chat-stream.js \
  --summary-export benchmarks/raw-results/k6-chat-stream-summary.json \
  | tee benchmarks/raw-results/k6-chat-stream-$(date +%Y%m%d-%H%M%S).txt
```

运行 k6（本机未安装，Docker）：

```bash
BASE_URL=http://host.docker.internal:8080 \
AUTH_TOKEN=$(node benchmarks/scripts/generate-benchmark-jwt.mjs) \
CONVERSATION_ID=91001 \
docker run --rm -i \
  -e BASE_URL \
  -e AUTH_TOKEN \
  -e CONVERSATION_ID \
  -e VUS=10 \
  -e DURATION=1m \
  -e SLEEP_SECONDS=1 \
  -v "$PWD/benchmarks/raw-results:/results" \
  -v "$PWD/benchmarks/scripts/k6-chat-stream.js:/scripts/k6-chat-stream.js:ro" \
  grafana/k6 run /scripts/k6-chat-stream.js \
    --summary-export /results/k6-chat-stream-summary.json \
  | tee benchmarks/raw-results/k6-chat-stream-$(date +%Y%m%d-%H%M%S).txt
```

### 5.3 真实模型压测

把 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`RAG_RERANK_API_URL`、`RAG_RERANK_API_KEY` 指向真实服务后重复 5.2。保存结果时文件名建议包含供应商、模型、并发和数据规模，例如：

```text
benchmarks/raw-results/k6-chat-stream-siliconflow-qwen-10vu-5000chunks-20260518-235900.txt
```

## 当前状态

- 已有脚本：是。
- 已有 pgvector 10 万向量单查询和 200 次采样分位数基准：是，见 `raw-results/pgvector-summary.md`。
- 已有 SSE 冒烟结果：是，`raw-results/sse-smoke-mock-500chunks-20260518-230700.txt` 证明 mock API 下链路可跑通。
- 已有 mock 端到端 k6 SSE 压测结果：是，`raw-results/k6-chat-stream-mock-1vu-500chunks-20260518-231000.txt`（1 VU / 5s / 500 chunks / mock API，仅验证链路与脚本）。
- 已有真实 LLM/Embedding/Rerank 端到端 k6 SSE 压测结果：否；需按 5.3 指向真实 API 后运行并落盘。
- README/简历只能引用已保存到 `raw-results/` 的数字；端到端延迟在 k6 结果生成前不得写成“已实测”。
