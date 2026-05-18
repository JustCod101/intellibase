# IntelliBase RAG 评测方案

> 当前阶段：Goal 2（评测先行）已落地离线评测骨架与 60 条 golden QA。后续每次检索链路变更都必须先跑本页命令，再把结果追加到“版本对比表”。

## 1. 评测目标

在重构 RAG 前先固定质量尺子，避免只凭主观感受判断“效果更好”。本项目优先评测：

| 类型 | 指标 | 含义 |
|---|---|---|
| 检索质量 | Recall@5 | Top 5 检索结果覆盖标准相关 chunk 的比例 |
| 检索质量 | MRR | 第一个相关 chunk 的倒数排名，越靠前越高 |
| 检索质量 | Hit Rate@5 | Top 5 是否至少命中一个相关 chunk |
| 生成质量 | Faithfulness | 答案是否被检索上下文支持 |
| 生成质量 | Answer Relevance | 答案是否正面回应问题 |

## 2. Golden QA 数据集

文件：`intellibase-server/src/test/resources/evaluation/golden_qa.jsonl`

当前共 **60 条**，覆盖 3 个面试相关知识域：

| 领域 | 数量 | 覆盖重点 |
|---|---:|---|
| `spring-boot-rabbitmq` | 20 | Spring Boot、JWT、RabbitMQ、MinIO、SSE、缓存一致性 |
| `postgres-pgvector-rag` | 20 | pgvector、Hybrid Search、RRF、Rerank、Query Rewrite、RAGAS 指标 |
| `java-jvm-concurrency` | 20 | 线程池、CompletableFuture、JMH/k6、背压、JVM 长尾延迟 |

每行 JSONL 结构：

```json
{
  "id": "PGV-001",
  "domain": "postgres-pgvector-rag",
  "question": "pgvector HNSW 和 IVFFlat 的核心区别是什么？",
  "referenceAnswer": "...",
  "relevantChunkIds": ["C-PGV-001"],
  "expectedKeywords": ["HNSW", "IVFFlat", "召回", "probes"]
}
```

## 3. 一键运行

```bash
cd intellibase-server
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) mvn -Dtest=RetrievalEvaluationTest test
```

输出文件：

- `intellibase-server/target/evaluation/baseline-report.md`
- `intellibase-server/target/evaluation/baseline-metrics.json`

## 4. 当前 baseline 结果

当前 baseline 使用 `baseline_run.jsonl` 固定样例作为评测管线 smoke test，目的是保证指标计算、报告输出和 CI 命令可运行；`db-backed-seeded-current` 使用真实 RetrievalService + PostgreSQL/pgvector，但语料与向量仍是 seeded deterministic corpus，不能替代真实文档评测。

| Version | Recall@5 | MRR | Hit Rate@5 | Faithfulness | Answer Relevance | 说明 |
|---|---:|---:|---:|---:|---:|---|
| baseline-fixture | 75.00% | 45.83% | 75.00% | 75.00% | 69.58% | 60 条 golden QA，固定 baseline_run.jsonl；命令已通过 |
| db-backed-seeded-current | 100.00% | 100.00% | 100.00% | 100.00% | 100.00% | 真实 RetrievalService + PostgreSQL/pgvector；seeded deterministic corpus，用于验证 DB-backed runner，不作为线上质量 claim |
| real-api dense-only | 100.00% | 94.64% | 100.00% | 100.00% | 92.17% | 60 条 golden QA 语料，真实 `text-embedding-v4` embedding，PostgreSQL/pgvector；raw: `real-api-evaluation-report-20260519-035801.md` |
| real-api hybrid RRF | 100.00% | 98.33% | 100.00% | 100.00% | 97.00% | pgvector + `tsvector`/GIN + RRF；同一真实 API run |
| real-api local rerank | 100.00% | 99.17% | 100.00% | 100.00% | 98.83% | 本地规则 rerank；同一真实 API run |
| real-api external rerank | 100.00% | 98.06% | 100.00% | 100.00% | 98.33% | DashScope `qwen3-rerank`；同一真实 API run |
| real-api query-rewrite | TBD | TBD | TBD | TBD | TBD | 本次 `.env` 中 `RAG_QUERY_REWRITE_ENABLED=false`，真实 rewrite / HyDE 指标待单独运行 |

## 5. RAGAS 风格 LLM-as-judge

CI 默认通过 `AnswerJudgeFactory` 选择评测器：未配置 API Key 时使用 `HeuristicAnswerJudge`，保证无外部依赖也能运行；配置以下环境变量后会启用 `OpenAiCompatibleAnswerJudge` 调用 OpenAI-compatible `/chat/completions` 做 LLM-as-judge。

```bash
export EVALUATION_LLM_JUDGE_API_KEY=sk-xxx
export EVALUATION_LLM_JUDGE_BASE_URL=https://api.openai.com/v1
export EVALUATION_LLM_JUDGE_MODEL=gpt-4o-mini
# 可选：真实评测默认 4 并发调用裁判模型，按 API 限流情况调低/调高。
export EVALUATION_LLM_JUDGE_CONCURRENCY=4
```

真实 LLM-as-judge 保持同一接口：

- 输入：question、referenceAnswer、retrieved contexts、generatedAnswer。
- 输出：`faithfulness`、`answerRelevance`、reason。
- 约束：走 OpenAI-compatible HTTP API，不引入 Python 服务。

## 6. DB-backed 检索评测 Runner

已新增 `DbBackedRetrievalEvaluationIT`，用于在真实 PostgreSQL/pgvector 上 seed golden corpus，并通过当前 `RetrievalService` 生成 TopK 结果，再复用同一个 `RetrievalMetricCalculator` 计算 Recall@5 / MRR / Hit Rate。

默认单元测试不依赖 Docker，因此该 runner 默认关闭。显式运行命令：

```bash
# 如果本机 5432 可直接访问 Docker Postgres
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) \
  mvn -pl intellibase-server \
  -Dtest=DbBackedRetrievalEvaluationIT \
  -Devaluation.db.enabled=true test

# 如果本机 5432 被系统 PostgreSQL 占用，可临时启动独立测试库
# docker run -d --name intellibase-eval-postgres \
#   -e POSTGRES_DB=intellibase -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
#   -p 55432:5432 \
#   -v "$PWD/sql/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro" \
#   pgvector/pgvector:pg16
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) \
  mvn -pl intellibase-server \
  -Dtest=DbBackedRetrievalEvaluationIT \
  -Devaluation.db.enabled=true \
  -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55432/intellibase test
```

输出文件：

- `intellibase-server/target/evaluation/db-backed-current-report.md`
- `intellibase-server/target/evaluation/db-backed-current-metrics.json`
- `intellibase-server/target/evaluation/db-backed-current-run.jsonl`

本机验证结果（2026-05-18，独立 `pgvector/pgvector:pg16` 容器，seeded deterministic corpus）：Recall@5 / MRR / Hit Rate 均为 100.00%。该结果只证明 DB-backed runner 能真实调用 RetrievalService + PostgreSQL/pgvector；真实文档和真实 embedding 的版本对比仍需继续补充。

后续要把 baseline → +hybrid → +rerank → +query rewrite 的提升幅度复现，需要：

1. 将 golden QA 对应的真实领域文档与真实 embedding 固化为可加载数据集；
2. 对每个版本生成 `target/evaluation/<version>_run.jsonl`；
3. 将结果追加到本页版本对比表和 README。

## 7. Versioned 检索链路对比 Runner

已新增 `VersionedRetrievalEvaluationIT`，用于在同一个 PostgreSQL/pgvector seeded corpus 上切换检索配置，验证 **baseline → hybrid → local rerank → query rewrite** 的评测矩阵、报告输出和回归断言。该 runner 使用 deterministic vectors 和启发式 judge，适合做工程回归测试；它不是“真实 embedding / 真实 rerank API / 真实 LLM rewrite”的质量 claim。

运行命令：

```bash
docker run -d --name intellibase-versioned-eval-postgres \
  -e POSTGRES_DB=intellibase -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 55435:5432 \
  -v "$PWD/sql/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro" \
  pgvector/pgvector:pg16

JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) \
  mvn -pl intellibase-server \
  -Dtest=VersionedRetrievalEvaluationIT \
  -Devaluation.versions.enabled=true \
  -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55435/intellibase test
```

本机验证结果（2026-05-18，独立 `pgvector/pgvector:pg16` 容器，seeded deterministic corpus；原始文件见 `benchmarks/raw-results/versioned-evaluation-report-20260518-232618.md`）：

| Version | Recall@5 | MRR | Hit Rate@5 | Faithfulness | Answer Relevance | 说明 |
|---|---:|---:|---:|---:|---:|---|
| baseline-dense-only | 0.00% | 0.00% | 0.00% | 100.00% | 1.67% | 向量近邻被故意设置为 distractor |
| hybrid-rrf | 98.33% | 44.53% | 98.33% | 100.00% | 1.67% | pgvector + `tsvector`/GIN 粗召回，RRF 融合；仅证明 recall 提升 |
| hybrid-local-rerank | 98.33% | 95.28% | 98.33% | 100.00% | 95.42% | 本地规则 rerank 将相关 chunk 提前 |
| hybrid-rerank-query-rewrite | 100.00% | 95.28% | 100.00% | 100.00% | 93.75% | deterministic rewrite 模拟检索友好 query |

补充实现细节：PostgreSQL sparse recall 使用应用层 tokenizer 预分词，`buildLexicalQuery` 生成 OR 型 `tsquery`（`token1 | token2 | ...`），避免 `plainto_tsquery` 对长问题做全 AND 匹配导致粗召回过窄；精排阶段再用 RRF / rerank 控制噪声。

## 8. Real API 版本对比 Runner

已新增 `RealApiRetrievalEvaluationIT`，用于生成可写入 README/简历的真实质量数字。它会：

1. 使用 `golden_qa.jsonl` 的 reference answer / keywords 构建 60 条评测语料；
2. 通过配置的真实 embedding API 生成 chunk 向量和 query 向量；
3. 在真实 PostgreSQL/pgvector 上依次运行 dense-only、hybrid RRF、local rerank；
4. 如果配置 `RAG_RERANK_API_URL` / `RAG_RERANK_API_KEY`，追加 external rerank 场景；
5. 如果开启 `RAG_QUERY_REWRITE_ENABLED=true`，追加真实 LLM query rewrite 场景；
6. 输出 `target/evaluation/real-api-comparison-report.md`、`real-api-comparison-metrics.json` 和每个版本的 run JSONL。

运行命令示例：

```bash
docker run -d --name intellibase-real-eval-postgres \
  -e POSTGRES_DB=intellibase -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 55437:5432 \
  -v "$PWD/sql/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro" \
  pgvector/pgvector:pg16

export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.openai.com/v1
export RAG_QUERY_REWRITE_ENABLED=true
# 可选：真实 rerank API
# export RAG_RERANK_API_URL=https://api.siliconflow.cn/v1/rerank
# export RAG_RERANK_API_KEY=sk-xxx
# export RAG_RERANK_EXTERNAL_ENABLED=true

JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) \
  mvn -pl intellibase-server \
  -Dtest=RealApiRetrievalEvaluationIT \
  -Devaluation.real-api.enabled=true \
  -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55437/intellibase test
```

也可以使用一行脚本自动启动临时 pgvector、运行测试并复制 raw results：

```bash
# 可以直接写入仓库根目录 .env；runner/preflight 会自动加载 .env。
# 也可以用 export 覆盖当前 shell 环境。

benchmarks/scripts/real-benchmark-preflight.sh retrieval

benchmarks/scripts/run-real-api-evaluation.sh
```

注意：`real-benchmark-preflight.sh` 只做本地环境预检，不调用外部 API；真正的 runner 会真实消耗 embedding / LLM / rerank API quota。只有把本 runner 的原始输出复制到 `benchmarks/raw-results/real-api-evaluation-*.md/json` 后，才允许把对应 Recall@5 / MRR / Hit Rate 写入 README 或简历。本仓库当前真实 API 结果为 `benchmarks/raw-results/real-api-evaluation-report-20260519-035801.md`。
