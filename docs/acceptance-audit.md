# IntelliBase 重构验收审计

> 审计日期：2026-05-19。本文把原始目标逐条映射到仓库证据，避免把 seeded fixture、mock API 或 DB-only 压测误写成生产级质量/性能结论。

## 1. 交付物映射

| 要求 | 当前证据 | 状态 | 备注 |
|---|---|---:|---|
| 重构后的代码覆盖现代 RAG | `RetrievalService`、`HybridRanker`、`QueryRewriteService`、`ExternalRerankService`、`TextSplitter`、`ChunkStrategy` | 已完成 | Java 单体内具备 hybrid / rerank / rewrite / parent-child；真实 API runner 已验证 embedding + external rerank |
| `docs/architecture.md` | `docs/architecture.md`，`docs/adr/*.md` | 已完成 | 包含现代 RAG 流程、缓存、ADR 索引 |
| `docs/evaluation.md` | `RetrievalEvaluationTest`、`DbBackedRetrievalEvaluationIT`、`VersionedRetrievalEvaluationIT`、`RealApiRetrievalEvaluationIT`、`real-api-evaluation-report-20260519-035801.md` | 已完成 | seeded matrix 覆盖 baseline→hybrid→rerank→rewrite；真实 API matrix 覆盖 dense/hybrid/local rerank/external rerank；真实 rewrite 单独标 TBD |
| `docs/deep-dive-<topic>.md` | `docs/deep-dive-pgvector-index-tuning.md` | 已完成 | 选择主题 C：pgvector 索引调优 |
| `benchmarks/` | `benchmarks/scripts/*`，`benchmarks/raw-results/*` | 已完成 | 已有 10 万数据、pgvector、mock SSE、真实 API retrieval、真实 SSE k6 原始结果 |
| README 更新 | `README.md` | 已完成 | 性能与质量数字均带 raw result 和前提条件 |
| `docs/resume-snippet.md` | `docs/resume-snippet.md` | 已完成 | 去虚化，区分 seeded/mock/DB-only/真实 API 指标 |

## 2. Goal 1：RAG 检索链路现代化

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| Hybrid Search：pgvector + `tsvector`/GIN + RRF | `RetrievalService.retrieveHybrid`、`SparseRecallService`、`DocumentChunkMapper.findLexicalMatches`、`HybridRanker` | 已完成 |
| Sparse recall 粗召回不过窄 | `LexicalTokenizer.buildLexicalQuery()` 生成 OR 型 `tsquery`；`LexicalTokenizerTest` 覆盖 | 已完成 |
| 二阶段 rerank | `HybridRanker` 本地 rerank；`ExternalRerankService` DashScope/OpenAI-compatible HTTP rerank；`real-api-evaluation-report-20260519-035801.md` | 已完成 |
| Query rewriting / HyDE | `QueryRewriteService`，`RagService` 使用 rewrite 后 retrievalText；配置项控制开关 | 已完成；真实 rewrite 指标未宣称 |
| Parent-child chunking | `ChunkStrategy.parentChildEnabled`，`TextSplitter` 父子分块，`RetrievalService.resolveGenerationContent` | 已完成 |

## 3. Goal 2：评测体系

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| 50–100 条 golden QA | `intellibase-server/src/test/resources/evaluation/golden_qa.jsonl`：60 条，3 个领域 | 已完成 |
| JUnit 输出 Recall@5 / MRR / Hit Rate | `RetrievalEvaluationTest`、`RetrievalMetricCalculator` | 已完成 |
| RAGAS 风格 LLM-as-judge | `AnswerJudgeFactory`、`HeuristicAnswerJudge`、`OpenAiCompatibleAnswerJudge`；真实报告含 faithfulness / answer relevance | 已完成 |
| baseline → +hybrid → +rerank → +query rewrite 表 | `VersionedRetrievalEvaluationIT`，`versioned-evaluation-report-20260518-232618.md` | 已完成（seeded regression matrix） |
| 真实 embedding / rerank 质量对比 | `RealApiRetrievalEvaluationIT`，`real-api-evaluation-report-20260519-035801.md` | 已完成；真实 query rewrite 因本次配置关闭仍 TBD，不写提升 claim |

真实 API retrieval 结果摘要（60 条 golden QA，DashScope-compatible `text-embedding-v4` / `qwen3-rerank` / LLM-as-judge）：

| 场景 | Recall@5 | MRR | Hit Rate | Faithfulness | Answer Relevance |
|---|---:|---:|---:|---:|---:|
| dense-only | 100.00% | 94.64% | 100.00% | 100.00% | 92.17% |
| hybrid RRF | 100.00% | 98.33% | 100.00% | 100.00% | 97.00% |
| local rerank | 100.00% | 99.17% | 100.00% | 100.00% | 98.83% |
| external rerank | 100.00% | 98.06% | 100.00% | 100.00% | 98.33% |

## 4. Goal 3：性能数据真实化

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| ≥10 万条文本/向量 fixture | `generate-100k-pgvector-fixtures.sql`、`generate-realtext-pgvector-fixtures.mjs`、`realtext-generate-100k-20260518-231500.txt` | 已完成，用于索引/规模压测 |
| pgvector IVFFlat vs HNSW 调优 | `pgvector-index-benchmark.sql`、`pgvector-latency-percentiles.sql`、`pgvector-summary.md` | 已完成 |
| P50/P95/P99 | `pgvector-latency-20260518-233100.txt`；`k6-chat-stream-real-20260519-163217.txt` | 已完成 |
| k6 核心接口压测 | `k6-chat-stream.js`，mock raw result，真实 raw result | 已完成 |
| README 中性能数字标注前提 | `README.md` 性能表 | 已完成 |

真实性能摘要：

- DB-only pgvector：100,000 条 1536 维 deterministic fixture vector；HNSW Top-20 200 samples P50/P95/P99 = 0.189/0.290/1.039ms；不包含 HTTP/Embedding/Rerank/LLM。
- 真实 SSE：`k6-chat-stream-real-20260519-163217.txt`，5 VUs / 1m / 220 requests，Docker k6 → IntelliBase app → DashScope-compatible `qwen3.6-plus` + `text-embedding-v4` + `qwen3-rerank`，seeded benchmark KB，query rewrite/HyDE 关闭，external rerank 开启；`http_req_failed=0%`，流式延迟 P50/P95/P99 = 203.5ms/1.613s/2.627s。

## 5. Goal 4：缓存简化

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| 保留 L1 语义缓存 + L2 检索缓存 | `SemanticCacheService`、`RetrievalCacheService` | 已完成 |
| 删除 L0 本地缓存 / L3 文档块缓存 | 删除 Caffeine chunk cache；文档块直接批量读 PostgreSQL | 已完成 |
| 相似度阈值可配置 | `rag.cache-similarity-threshold` 默认 0.95 | 已完成 |
| 语义缓存命中后 sanity check | `SemanticCacheServiceTest` 覆盖 Top1 假阳性拒绝、候选回退与关闭开关 | 已完成 |
| 文档更新按 `kb_id` 失效 | `CacheEvictionService`、`CacheEvictionServiceTest` | 已完成 |

## 6. Goal 5：深度故事

| 验收点 | 当前证据 | 状态 |
|---|---|---:|
| 任选一个主题深挖 | 选择 C：pgvector 索引调优 | 已完成 |
| 问题、方案对比、踩坑、最终方案、数据验证 | `docs/deep-dive-pgvector-index-tuning.md` | 已完成 |

## 7. 剩余边界与不得夸大的点

1. 真实 query rewrite / HyDE 本轮未开启，因此不能写“真实 API query rewrite 提升”；只能写代码支持、seeded regression matrix 已覆盖。
2. 10 万 real-text fixture 的向量仍是 deterministic fixture vector，只能证明规模、索引和导入能力；不能写成“10 万真实 embedding 语义质量”。
3. SSE k6 使用 seeded benchmark KB 和固定 5 个问题，适合作为可复现端到端性能证据；不能外推为生产流量全局 P95/P99。

## 8. Artifact verifier

`node benchmarks/scripts/verify-benchmark-artifacts.mjs --strict` 已通过，覆盖以下 artifact 类别：100k synthetic fixture、100k real-text fixture、pgvector index comparison、pgvector percentile latency、seeded versioned retrieval matrix、mock SSE k6、real API retrieval matrix、real SSE k6 benchmark。该 verifier 只验证关键证据存在和格式/指标字段完整；发布数字前仍需人工核对模型、供应商、硬件、数据规模和命令。

## 9. Prompt-to-artifact checklist

| 原始要求/验收项 | 具体证据 | 当前判定 |
|---|---|---:|
| 保持单一 Java 后端 | `pom.xml` 单 Java server 模块；`docker-compose.yml` 为 app + 基础设施 | 已满足 |
| 禁止 Python 服务 | 无新增 Python runtime service；旧 `scripts/system_test` 仅测试脚本 | 已满足 |
| 不引入微服务/复杂 Agent 框架 | query rewrite 在 `QueryRewriteService` 内完成，无 ReAct/multi-agent | 已满足 |
| Hybrid Search + RRF | `RetrievalService`、`SparseRecallService`、`HybridRanker` | 已满足 |
| 二阶段 rerank Top20–50 → Top3–5 | `ExternalRerankService`、`HybridRetrievalProperties`、真实 API report | 已满足 |
| Query Rewriting / 可选 HyDE | `QueryRewriteService`、配置项、seeded matrix | 已满足；真实指标不宣称 |
| Parent-Child Chunking 可配置 | `ChunkStrategy`、`TextSplitter`、`TextSplitterTest` | 已满足 |
| Golden QA 50–100 条，覆盖 2–3 域 | `golden_qa.jsonl` 60 行，3 domains | 已满足 |
| 离线评测 JUnit 输出 Recall@5/MRR/Hit Rate | `RetrievalEvaluationTest`、`RetrievalMetricCalculator` | 已满足 |
| RAGAS 风格 LLM-as-judge | `OpenAiCompatibleAnswerJudge`、真实 API report | 已满足 |
| baseline → +hybrid → +rerank → +query rewrite 表 | `versioned-evaluation-report-20260518-232618.md` | 已满足（seeded） |
| ≥10 万文本/向量数据生成 | `realtext-generate-100k-20260518-231500.txt` | 已满足 |
| JMH 或 k6 压测核心接口 | `k6-chat-stream-real-20260519-163217.txt` | 已满足 |
| pgvector IVFFlat lists/probes vs HNSW 调优 | `pgvector-20260518-233030.txt`、`pgvector-summary.md` | 已满足 |
| P50/P95/P99 | `pgvector-latency-20260518-233100.txt`、`k6-chat-stream-real-20260519-163217.txt` | 已满足 |
| 缓存减为 2 层，失效逻辑单测 | `SemanticCacheServiceTest`、`CacheEvictionServiceTest` | 已满足 |
| 深度故事文档 | `docs/deep-dive-pgvector-index-tuning.md` | 已满足 |
| README/简历去虚化、带前提 | `README.md`、`docs/resume-snippet.md` | 已满足 |
| 最终门禁 | `benchmarks/scripts/final-acceptance-gate.sh` | 已通过（2026-05-19 16:38，91 tests + strict verifier） |

## 10. 最终门禁命令

```bash
benchmarks/scripts/final-acceptance-gate.sh
```

该脚本执行：JDK/Maven 检查、golden QA 数量检查、benchmark shell/Node 脚本语法检查、claim hygiene、`mvn -pl intellibase-server test`、`node benchmarks/scripts/verify-benchmark-artifacts.mjs --strict`。任何真实 artifact 缺失都会导致失败。
