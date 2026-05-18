# IntelliBase 重构验收审计

> 审计日期：2026-05-18。本文把原始目标逐条映射到当前仓库证据，避免把 seeded fixture、mock API 或通过测试误当成真实线上质量/性能结论。

## 1. 交付物映射

| 要求 | 当前证据 | 状态 | 备注 |
|---|---|---:|---|
| 重构后的代码覆盖现代 RAG | `RetrievalService`、`HybridRanker`、`QueryRewriteService`、`ExternalRerankService`、`TextSplitter`、`ChunkStrategy` | 部分完成 | 代码已具备 hybrid / rerank / rewrite / parent-child，但真实 API 质量对比尚未跑出 |
| `docs/architecture.md` | `docs/architecture.md` | 已完成 | 包含现代 RAG 流程、缓存、ADR 索引 |
| `docs/evaluation.md` | `docs/evaluation.md`，`RetrievalEvaluationTest`，`DbBackedRetrievalEvaluationIT`，`VersionedRetrievalEvaluationIT`，`RealApiRetrievalEvaluationIT` | 部分完成 | seeded 与 real-api runner 均有；真实 API 原始结果未生成 |
| `docs/deep-dive-<topic>.md` | `docs/deep-dive-pgvector-index-tuning.md` | 已完成 | 选择主题 C：pgvector 索引调优 |
| `benchmarks/` | `benchmarks/scripts/*`，`benchmarks/raw-results/*` | 部分完成 | pgvector 与 mock SSE 有原始输出；真实 LLM/Embedding/Rerank 端到端 k6 未运行 |
| README 更新 | `README.md` | 部分完成 | 所有已写数字均带前提；真实质量/真实端到端性能仍标注待补 |
| `docs/resume-snippet.md` | `docs/resume-snippet.md` | 部分完成 | 已去虚化；真实 API 指标未跑出，不能写最终质量 claim |

## 2. Goal 1：RAG 检索链路现代化

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| Hybrid Search：pgvector + tsvector/GIN + RRF | `RetrievalService.retrieveHybrid`、`SparseRecallService`、`DocumentChunkMapper.findLexicalMatches`、`HybridRanker` | 已完成 |
| Sparse recall 粗召回不过窄 | `LexicalTokenizer.buildLexicalQuery()` 生成 OR 型 `tsquery`；`LexicalTokenizerTest` 覆盖 | 已完成 |
| 二阶段 rerank | `HybridRanker` 本地 rerank；`ExternalRerankService` OpenAI-compatible rerank API | 已实现，真实外部 API 未验证 |
| Query rewriting / HyDE | `QueryRewriteService`，`RagService` 使用 rewrite 后 retrievalText | 已实现，真实 API 未验证 |
| Parent-child chunking | `ChunkStrategy.parentChildEnabled`，`TextSplitter` 父子分块，`RetrievalService.resolveGenerationContent` | 已完成 |

## 3. Goal 2：评测体系

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| 50-100 条 golden QA | `intellibase-server/src/test/resources/evaluation/golden_qa.jsonl`：60 条，3 个领域 | 已完成 |
| JUnit 输出 Recall@5 / MRR / Hit Rate | `RetrievalEvaluationTest`、`RetrievalMetricCalculator` | 已完成 |
| RAGAS 风格 LLM-as-judge | `AnswerJudgeFactory`、`HeuristicAnswerJudge`、`OpenAiCompatibleAnswerJudge` | 已完成，真实 judge 需配置 API |
| baseline → +hybrid → +rerank → +query rewrite 表 | `VersionedRetrievalEvaluationIT` 和 `benchmarks/raw-results/versioned-evaluation-report-20260518-232618.md` | seeded 完成，真实 API 待跑 |
| 真实 embedding / rewrite / rerank 版本对比 | `RealApiRetrievalEvaluationIT` | runner 已完成，原始结果缺失 |

## 4. Goal 3：性能数据真实化

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| ≥10 万条文本/向量 fixture | `generate-100k-pgvector-fixtures.sql`、`generate-realtext-pgvector-fixtures.mjs`、raw results | 已完成用于 DB 索引/规模压测 |
| pgvector IVFFlat vs HNSW 调优 | `pgvector-index-benchmark.sql`、`pgvector-latency-percentiles.sql`、`pgvector-summary.md` | 已完成 |
| P50/P95/P99 | `pgvector-latency-20260518-233100.txt` | DB 检索已完成 |
| k6 核心接口压测 | `k6-chat-stream.js`、mock raw result | mock 完成，真实 LLM/Embedding/Rerank 端到端缺失 |
| README 中性能数字标注前提 | `README.md` | 已完成；真实端到端仍未填写 |

## 5. Goal 4：缓存简化

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| 保留 L1 语义缓存 + L2 检索缓存 | `SemanticCacheService`、`RetrievalCacheService` | 已完成 |
| 删除 L0 本地缓存 / L3 文档块缓存 | 删除 Caffeine chunk cache；文档块直接批量读 PostgreSQL | 已完成 |
| 相似度阈值可配置 | `rag.cache-similarity-threshold` | 已完成 |
| 语义缓存命中后 sanity check | `SemanticCacheServiceTest` 覆盖 Top1 假阳性拒绝、候选回退与关闭开关 | 已完成 |
| 文档更新按 `kb_id` 失效 | `CacheEvictionService`、`CacheEvictionServiceTest` | 已完成 |

## 6. Goal 5：深度故事

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| 任选一个主题深挖 | 选择 C：pgvector 索引调优 | 已完成 |
| 问题、方案对比、踩坑、最终方案、数据验证 | `docs/deep-dive-pgvector-index-tuning.md` | 已完成 |

## 7. 当前必须补齐后才能宣称整体完成

1. 运行 `benchmarks/scripts/run-real-api-evaluation.sh`（封装 `RealApiRetrievalEvaluationIT`），生成真实 embedding / 可选真实 rewrite / external rerank 的版本对比原始结果，并把结果复制到 `benchmarks/raw-results/real-api-evaluation-*`。
2. 使用真实 LLM/Embedding/Rerank API 运行 `benchmarks/scripts/run-real-chat-stream-k6.sh`，输出 `/api/v1/chat/stream` P50/P95/P99；mock k6 不能替代真实端到端性能。
3. 如果要在简历写“rerank 提升”“query rewrite 提升”，必须引用第 1 步真实 API 报告，而不是 seeded matrix。
4. 若要宣称“10 万真实 embedding 语义质量”，还需用真实 embedding 对真实领域文本构建 10 万向量；当前 10 万 real-text fixture 的向量是 deterministic fixture vector，仅适合索引/规模压测。

## 8. Artifact verifier

`node benchmarks/scripts/verify-benchmark-artifacts.mjs` 已验证当前 raw-results 中 real API retrieval matrix 与 real SSE k6 benchmark 仍缺失；`--strict` 模式会在缺失时返回非零，适合最终验收前使用。

## 9. 2026-05-18 completion audit（按原始 prompt 全量核对）

### 9.1 目标重述为可验收交付物

本轮重构只有在以下条件都满足时才能宣称完成：

1. **代码交付**：Java 单体后端内落地 hybrid search、external rerank、query rewrite/HyDE、parent-child chunking，并保持 Spring Boot 3.2 + LangChain4j + PostgreSQL/pgvector + Redis/RabbitMQ/MinIO 技术栈。
2. **评测交付**：50–100 条 golden QA、JUnit 一键输出 Recall@5/MRR/Hit Rate、RAGAS 风格 judge、baseline → hybrid → rerank → rewrite 对比结果。
3. **性能交付**：≥10 万文本/向量数据、pgvector IVFFlat/HNSW 参数对比、P50/P95/P99、k6 核心接口压测、README 中所有性能数字都有 raw result 和复现命令。
4. **缓存交付**：仅保留 L1 语义缓存与 L2 检索缓存，删除 L0/L3；语义缓存阈值可配置、命中后做 sanity check、文档更新按 `kb_id` 失效且有单测。
5. **深度故事与文档交付**：`docs/architecture.md`、`docs/evaluation.md`、`docs/deep-dive-<topic>.md`、`benchmarks/`、`README.md`、`docs/resume-snippet.md`、`docs/adr/*.md` 都存在且内容与真实证据一致。
6. **约束交付**：不引入 Python 服务、不改微服务架构、不引入复杂 Agent 框架；所有外部 embedding/rerank/judge 都通过 HTTP API；未验证指标不得写成简历 claim。

### 9.2 本次审计实际执行的证据命令

| 命令 | 结果 | 结论 |
|---|---|---|
| `git status --short` | 无未提交变更 | 工作区干净 |
| `wc -l intellibase-server/src/test/resources/evaluation/golden_qa.jsonl` | 60 | golden QA 数量满足 50–100 |
| `JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) mvn -pl intellibase-server test` | 89 tests, 0 failures, 0 errors, 0 skipped | 本地单元/默认集成测试通过 |
| `node benchmarks/scripts/verify-benchmark-artifacts.mjs --strict` | `strict_verifier_exit=1`；缺 `real API retrieval matrix` 与 `real SSE k6 benchmark` | 总目标不能完成 |
| `benchmarks/scripts/final-acceptance-gate.sh` | 新增最终门禁：单测、脚本语法、golden QA 数量、strict artifact verifier；当前因同样两个真实 artifact 缺失而失败 | 总目标不能完成 |
| `find docs/adr -name '*.md'` | 3 个 ADR：评测先行、性能数据、现代 RAG/缓存 | ADR 要求已有最小覆盖 |
| `find . -maxdepth 4 -type f \( -name '*.py' -o -name 'requirements.txt' \)` | 仅发现旧的 `intellibase-server/scripts/system_test/*` 测试脚本 | 未发现新增 Python 服务；运行时仍为 Java app + 基础设施 |

### 9.3 Prompt-to-artifact checklist

| 原始要求/验收项 | 具体证据 | 当前判定 |
|---|---|---:|
| 保持单一 Java 后端 | `pom.xml` 仅 `intellibase-server` 模块；`docker-compose.yml` 只有 `app` Java 服务与 postgres/redis/rabbitmq/minio | 已满足 |
| 禁止 Python 服务 | 没有新增 Python runtime service；旧 `scripts/system_test` 仅测试脚本 | 已满足 |
| 不引入微服务/复杂 Agent 框架 | 未发现 ReAct/multi-agent 框架；query rewrite 在 `QueryRewriteService` 内部完成 | 已满足 |
| Hybrid Search：pgvector + PostgreSQL 全文检索并行召回 | `RetrievalService.retrieveHybrid`、`SparseRecallService`、`DocumentChunkMapper.findLexicalMatches` | 已满足 |
| RRF 融合排序 | `HybridRanker` | 已满足 |
| 二阶段 rerank Top20–50 → Top3–5 | `ExternalRerankService`、`HybridRetrievalProperties`、`RetrievalService` | 代码满足；真实外部 rerank API 未跑 |
| Query Rewriting / 可选 HyDE | `QueryRewriteService`、`RagService` | 代码满足；真实 rewrite/HyDE API 未跑 |
| Parent-Child Chunking 可配置 | `ChunkStrategy`、`TextSplitter`、`RetrievalService.resolveGenerationContent`、`TextSplitterTest` | 已满足 |
| Golden QA 50–100 条，覆盖 2–3 域 | `golden_qa.jsonl` 60 行，3 domains | 已满足 |
| 离线评测 JUnit 输出 Recall@5/MRR/Hit Rate | `RetrievalEvaluationTest`、`RetrievalMetricCalculator` | 已满足 |
| RAGAS 风格 LLM-as-judge | `AnswerJudgeFactory`、`HeuristicAnswerJudge`、`OpenAiCompatibleAnswerJudge` | 本地 heuristic 可用；真实 judge API 未跑 |
| baseline → +hybrid → +rerank → +query rewrite 表 | `VersionedRetrievalEvaluationIT`、`benchmarks/raw-results/versioned-evaluation-report-20260518-232618.md` | seeded matrix 已满足；真实 API matrix 缺失 |
| ≥10 万文本/向量数据生成 | `generate-100k-pgvector-fixtures.sql`、`generate-realtext-pgvector-fixtures.mjs`、`realtext-generate-100k-20260518-231500.txt` | 索引/规模压测满足；真实 embedding 语义质量不满足 |
| JMH 或 k6 压测核心接口 | `k6-chat-stream.js`、mock raw result、`run-real-chat-stream-k6.sh` | mock 满足脚手架；真实端到端 k6 缺失 |
| pgvector IVFFlat lists/probes vs HNSW 调优 | `pgvector-index-benchmark.sql`、`pgvector-20260518-233030.txt`、`pgvector-summary.md` | 已满足 |
| P50/P95/P99 | `pgvector-latency-percentiles.sql`、`pgvector-latency-20260518-233100.txt` | DB 检索满足；真实接口 P50/P95/P99 缺失 |
| README 性能数字标注前提条件 | `README.md` 性能表与 raw-results 链接 | 已满足，且真实缺口已注明 |
| 缓存减为 2 层 | `SemanticCacheService`、`RetrievalCacheService`；无 `ChunkCacheService` | 已满足 |
| 删除 L0/L3 | grep 未发现 Caffeine/ChunkCacheService 运行代码 | 已满足 |
| 语义缓存阈值可配置 ≥0.95 建议 | `rag.cache-similarity-threshold: 0.95` | 已满足 |
| 语义缓存命中后 sanity check | `rag.cache-sanity-*`、`SemanticCacheServiceTest` | 已满足 |
| 文档更新按 `kb_id` 失效且单测 | `CacheEvictionServiceTest`、`CacheEvictionService` | 已满足 |
| 任选一个深度故事 | 选择 C pgvector 索引调优 | 已满足 |
| `docs/deep-dive-<topic>.md` 含问题、方案、踩坑、最终方案、数据验证 | `docs/deep-dive-pgvector-index-tuning.md` | 已满足 |
| 所有架构决策写到 `docs/adr/` | `0001` 评测先行、`0002` 性能数据、`0003` RAG/缓存 | 已满足最小覆盖 |
| 每个子任务 conventional commit | 最新提交如 `feat: add semantic cache sanity check`，历史提交也为 `feat/test/docs/fix` 前缀 | 已满足 |
| `docs/architecture.md` | 文件存在并描述现代 RAG、缓存与 ADR | 已满足 |
| `docs/evaluation.md` | 文件存在并区分 seeded 与真实 API 待跑 | 部分满足：真实 API 结果缺失 |
| `benchmarks/` | 脚本与 raw-results 存在 | 部分满足：真实 API/k6 raw result 缺失 |
| `docs/resume-snippet.md` 去虚化带前提 | 文件存在，明确不要夸大 seeded/mock | 部分满足：缺真实 API 指标后只能作为保守模板 |
| docker compose / `.env` 暴露真实 rewrite/rerank 配置 | `.env.example`、`docker-compose.yml`、`benchmarks/README.md` | 已满足：真实 k6 前可通过环境变量启用外部 rewrite/rerank |

### 9.4 不能完成的阻塞项

当前仓库已经具备 runner 和复现脚本，但审计结论仍是 **未完成**，因为缺少外部凭证和真实运行结果：

1. `OPENAI_API_KEY` 未配置，无法运行 `benchmarks/scripts/run-real-api-evaluation.sh` 生成真实 embedding / rewrite / judge 的 matrix。
2. `RAG_RERANK_API_KEY` / `RAG_RERANK_API_URL` 未配置，无法验证真实 external rerank 效果。
3. 缺真实应用会话参数 `AUTH_TOKEN` / `CONVERSATION_ID` 与真实 API 环境，无法运行 `benchmarks/scripts/run-real-chat-stream-k6.sh` 生成真实 `/api/v1/chat/stream` P50/P95/P99。
4. 因上述缺口，`node benchmarks/scripts/verify-benchmark-artifacts.mjs --strict` 仍返回非零；这是最终发布/简历 claim 前的硬门禁。

### 9.5 最终门禁命令

已补充一条最终本地验收命令，避免人工漏跑局部检查：

```bash
benchmarks/scripts/final-acceptance-gate.sh
```

该脚本默认执行：

1. JDK 可用性检查；
2. `golden_qa.jsonl` 数量必须在 50–100；
3. benchmark shell / Node.js 脚本语法检查；
4. `mvn -pl intellibase-server test`；
5. `node benchmarks/scripts/verify-benchmark-artifacts.mjs --strict`。

在真实 API retrieval matrix 与真实 SSE k6 benchmark raw result 缺失前，该脚本必须失败，不能作为完成信号。
