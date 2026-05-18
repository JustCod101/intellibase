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
