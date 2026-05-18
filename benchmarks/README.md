# IntelliBase Benchmarks

本目录只记录可复现的性能测试脚本、原始结果和测试前提。任何 README/简历中的性能数字都必须能追溯到这里。

## 目录

| 路径 | 说明 |
|---|---|
| `scripts/generate-100k-pgvector-fixtures.sql` | PostgreSQL 内生成 10 万条 `document_chunk` + 1536 维向量测试数据 |
| `scripts/generate-realtext-pgvector-fixtures.mjs` | 从仓库真实代码/文档/SQL 切分并平铺到指定规模，生成可 pipe 给 `psql` 的 real-text fixture SQL |
| `scripts/seed-chat-benchmark.sql` | 为 SSE 端到端压测准备租户、用户、知识库、会话和检索分块 |
| `scripts/generate-benchmark-jwt.mjs` | 生成与 benchmark fixture 匹配的 JWT（避免依赖登录接口造数） |
| `scripts/mock-openai-server.mjs` | 本地 OpenAI-compatible mock：embeddings / streaming chat / rerank，用于链路冒烟与压测脚本调通 |
| `scripts/pgvector-index-benchmark.sql` | HNSW / IVFFlat 参数对比 SQL（构建时间、查询延迟、召回候选） |
| `scripts/pgvector-latency-percentiles.sql` | 多查询 HNSW/GIN P50/P95/P99 延迟采样 SQL |
| `scripts/k6-chat-stream.js` | SSE `/api/v1/chat/stream` 端到端压测脚本 |
| `scripts/run-real-api-evaluation.sh` | 真实 embedding / 可选 rewrite / rerank 的质量评测一键 runner |
| `scripts/run-real-chat-stream-k6.sh` | 真实 `/api/v1/chat/stream` k6 一键 runner |
| `scripts/verify-benchmark-artifacts.mjs` | 检查 raw-results 是否覆盖验收所需 artifact 类别，并对最新文件做关键内容校验；`--strict` 缺失或内容不合格时返回非零 |
| `scripts/final-acceptance-gate.sh` | 最终本地验收门禁：单测、脚本语法、golden set 数量和 strict artifact verifier |
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

### 2.1 快速 synthetic fixture（已用于当前 pgvector 原始结果）

```bash
psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v fixture_kb_id=90001 \
  -v fixture_rows=100000 \
  -f benchmarks/scripts/generate-100k-pgvector-fixtures.sql
```

### 2.2 real-text fixture（推荐用于后续真实性能 claim）

从当前仓库的真实 Java/Markdown/SQL/YAML/TS 等文件切分文本，并平铺到 10 万条 `document_chunk`。向量仍是 deterministic fixture vector，用于索引规模和延迟压测；如果要证明真实语义质量，仍需接真实 Embedding API 生成向量。

```bash
REALTEXT_ROWS=100000 \
REALTEXT_KB_ID=92001 \
node benchmarks/scripts/generate-realtext-pgvector-fixtures.mjs \
  | psql postgresql://postgres:postgres@localhost:5432/intellibase \
  | tee benchmarks/raw-results/realtext-generate-100k-$(date +%Y%m%d-%H%M%S).txt
```

脚本默认排除 `.git`、`node_modules`、`target`、`dist`、`raw-results` 等目录，避免把依赖包或历史压测结果当成语料。

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

### 5.3 真实质量评测与真实模型压测

真实检索质量（真实 embedding，按配置可选真实 query rewrite / external rerank）：

```bash
OPENAI_API_KEY=sk-xxx OPENAI_BASE_URL=https://api.openai.com/v1 \
RAG_QUERY_REWRITE_ENABLED=true \
RAG_RERANK_API_URL=https://api.example.com/v1/rerank \
RAG_RERANK_API_KEY=sk-xxx \
  benchmarks/scripts/run-real-api-evaluation.sh
```

真实 SSE 端到端压测前，先用真实 `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `RAG_RERANK_API_URL` / `RAG_RERANK_API_KEY` 启动 IntelliBase，并准备好 `AUTH_TOKEN`、`CONVERSATION_ID`。然后运行：

```bash
AUTH_TOKEN=xxx CONVERSATION_ID=91001 BASE_URL=http://localhost:8080 \
VUS=10 DURATION=1m \
  benchmarks/scripts/run-real-chat-stream-k6.sh
```

如果使用 `docker compose up app` 启动应用，确保 `.env` 中显式配置：

```dotenv
OPENAI_API_KEY=sk-xxx
OPENAI_BASE_URL=https://api.openai.com/v1
LLM_MODEL_NAME=gpt-4o-mini
RAG_QUERY_REWRITE_ENABLED=true
RAG_HYDE_ENABLED=false
RAG_RERANK_EXTERNAL_ENABLED=true
RAG_RERANK_API_URL=https://api.example.com/v1/rerank
RAG_RERANK_API_KEY=sk-xxx
RAG_RERANK_MODEL=bge-reranker-v2-m3
```

保存结果时文件名会包含 `real` 和时间戳。runner 会额外生成 `real-api-evaluation-metadata-*` 或 `k6-chat-stream-real-metadata-*`，并把关键 metadata 写入真实 report/log；发布 README/简历性能数字前，仍需人工核对供应商、模型、数据规模、并发和硬件。

## 6. Artifact 验收检查

```bash
node benchmarks/scripts/verify-benchmark-artifacts.mjs
# CI/最终验收可使用严格模式：
node benchmarks/scripts/verify-benchmark-artifacts.mjs --strict
```

该检查会验证 raw result 文件类别是否齐全，并检查最新文件中是否包含关键规模/指标/场景证据（例如 100000 chunks、P50/P95/P99、hybrid/rerank/rewrite 场景、真实结果 metadata、k6 thresholds），同时检查 metrics/summary/metadata companion 文件是否按同一时间戳成对存在。它仍不会证明数字本身合理；发布前仍需人工核对每个原始文件的模型、供应商、硬件、数据规模和命令。

## 7. 最终验收门禁

在准备把 README/简历指标标记为“已实测”前运行：

```bash
benchmarks/scripts/final-acceptance-gate.sh
```

该脚本会检查 JDK/Maven 单测、脚本语法、golden QA 数量，并以 `verify-benchmark-artifacts.mjs --strict` 作为硬门禁。当前在真实 API retrieval matrix 和真实 SSE k6 raw result 缺失时会故意失败。

## 当前状态

- 已有脚本：是。
- 已有 pgvector 10 万向量单查询和 200 次采样分位数基准：是，见 `raw-results/pgvector-summary.md`。
- 已有 real-text fixture 10 万导入结果：是，`raw-results/realtext-generate-100k-20260518-231500.txt`（100000 chunks，196 个源码/文档文件，708 个去重 chunk 文本；导入耗时 57.040s，向量为 deterministic fixture vector）。
- 已有 SSE 冒烟结果：是，`raw-results/sse-smoke-mock-500chunks-20260518-230700.txt` 证明 mock API 下链路可跑通。
- 已有 mock 端到端 k6 SSE 压测结果：是，`raw-results/k6-chat-stream-mock-1vu-500chunks-20260518-231000.txt`（1 VU / 5s / 500 chunks / mock API，仅验证链路与脚本）。
- 已有真实 LLM/Embedding/Rerank 端到端 k6 SSE 压测结果：否；需按 5.3 指向真实 API 后运行并落盘。
- README/简历只能引用已保存到 `raw-results/` 的数字；端到端延迟在 k6 结果生成前不得写成“已实测”。
