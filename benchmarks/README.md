# IntelliBase Benchmarks

本目录只记录可复现的性能测试脚本、原始结果和测试前提。任何 README/简历中的性能数字都必须能追溯到这里。

## 目录

| 路径 | 说明 |
|---|---|
| `scripts/generate-100k-pgvector-fixtures.sql` | PostgreSQL 内生成 10 万条 `document_chunk` + 1536 维向量测试数据 |
| `scripts/pgvector-index-benchmark.sql` | HNSW / IVFFlat 参数对比 SQL（构建时间、查询延迟、召回候选） |
| `scripts/pgvector-latency-percentiles.sql` | 多查询 HNSW/GIN P50/P95/P99 延迟采样 SQL |
| `scripts/k6-chat-stream.js` | SSE `/api/v1/chat/stream` 端到端压测脚本 |
| `raw-results/` | 保存每次压测原始输出，禁止只贴结论 |

## 前置条件

- JDK 17
- Docker / Docker Compose
- PostgreSQL 16 + pgvector（`docker-compose.yml` 已提供）
- k6（HTTP/SSE 端到端压测）
- 可用的 OpenAI-compatible LLM/Embedding API（端到端 RAG 压测需要）

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

```bash
BASE_URL=http://localhost:8080 \
AUTH_TOKEN=<jwt> \
CONVERSATION_ID=<conversation-id> \
k6 run benchmarks/scripts/k6-chat-stream.js \
  --summary-export benchmarks/raw-results/k6-chat-stream-summary.json \
  | tee benchmarks/raw-results/k6-chat-stream-$(date +%Y%m%d-%H%M%S).txt
```

## 当前状态

- 已有脚本：是。
- 已有 pgvector 10 万向量单查询和 200 次采样分位数基准：是，见 `raw-results/pgvector-summary.md`。
- 已有端到端 k6 SSE 压测结果：否，缺少真实 LLM/Embedding API 与认证 token。
- README/简历只能引用已保存到 `raw-results/` 的数字；端到端延迟在 k6 结果生成前不得写成“已实测”。
