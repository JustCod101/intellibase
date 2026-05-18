# IntelliBase 简历项目描述模板

## 一句话版本

IntelliBase 是一个基于 Spring Boot 3.2、LangChain4j、PostgreSQL/pgvector、Redis、RabbitMQ、MinIO 的 Java 单体 RAG 知识库系统，重点实现评测驱动的现代检索链路、异步文档处理和可复现性能压测。

## 项目职责描述

- 设计并实现 RAG 检索链路：pgvector 语义召回 + PostgreSQL `tsvector`/GIN 全文召回，通过 RRF 融合排序；支持可配置 Query Rewrite / HyDE 和外部 rerank API 二阶段精排。
- 建立离线评测体系：构造 60 条 golden QA，覆盖 Spring/RabbitMQ、PostgreSQL/pgvector/RAG、Java/JVM 并发 3 个知识域；JUnit 一键输出 Recall@5、MRR、Hit Rate 与 RAGAS 风格 faithfulness / answer relevance。
- 落地父子分块策略：子块用于向量检索，命中后使用父块上下文拼装 Prompt，提高长文档回答的上下文完整性。
- 简化缓存架构：删除 L0 JVM 本地缓存与 L3 文档块缓存，保留 L1 语义缓存和 L2 Redis 检索结果缓存；L1 命中增加 token overlap sanity check，文档更新按 `kb_id` 统一失效，降低缓存一致性风险。
- 建立性能复现目录：提供 10 万条 pgvector fixture 生成 SQL、real-text fixture 生成脚本、HNSW/IVFFlat 参数对比 SQL、k6 SSE 压测脚本；性能数字必须附数据规模、并发、硬件和原始结果。

## 当前可量化口径（不要夸大）

- 评测集：60 条 golden QA，3 个知识域。
- baseline-fixture：Recall@5 75.00%、MRR 45.83%、Hit Rate@5 75.00%（固定样例，仅验证评测管线）。
- DB-backed seeded runner：真实 PostgreSQL/pgvector + RetrievalService，Recall@5/MRR/Hit Rate = 100.00%（deterministic corpus，仅证明 runner 可用）。
- Versioned seeded matrix：dense-only → hybrid RRF → local rerank → query rewrite 的 Recall@5 = 0.00% → 98.33% → 98.33% → 100.00%；用于回归验证，不作为真实 embedding / 外部 rerank API 质量 claim。
- RealApiRetrievalEvaluationIT：真实 `text-embedding-v4` + PostgreSQL/pgvector + DashScope `qwen3-rerank` + LLM-as-judge 已跑通；60 条 golden QA 下 dense-only / hybrid RRF / local rerank / external rerank 的 Recall@5 均为 100.00%，MRR 为 94.64% / 98.33% / 99.17% / 98.06%。前提：golden QA 语料，不代表生产流量；raw result `benchmarks/raw-results/real-api-evaluation-report-20260519-035801.md`。
- pgvector 基准：100,000 条 1536 维向量 fixture；HNSW Top-20（`ef_search=40`）单查询 `EXPLAIN ANALYZE` 0.426ms；200 次采样 HNSW Top-20 P50/P95/P99 = 0.189/0.290/1.039ms；IVFFlat(`lists=100, probes=20`) 单查询 150.876ms。前提：本机 Docker PostgreSQL 16 + pgvector，不含 HTTP/Embedding/Rerank/LLM。
- real-text 数据规模：已提供从仓库真实代码/文档/SQL 切分并平铺到 100,000 chunks 的脚本，实测导入 57.040s；去重 chunk 文本 708 个，向量仍为 deterministic fixture vector，因此只作为规模/索引压测数据，不作为语义质量证明。
- SSE 压测：mock OpenAI-compatible API 下 1 VU/5s/500 chunks 链路已跑通，`http_req_failed=0%`；真实 LLM/Embedding/Rerank 延迟仍需接真实 API 后填写。

## 面试展开点

1. 为什么先做评测：没有 Recall@5/MRR/Hit Rate 就无法证明 hybrid/rerank/rewrite 带来收益。
2. 为什么选两层缓存：L0/L3 的命中收益未被数据证明，但会显著增加文档更新一致性成本。
3. 为什么父子分块：小块提升召回精度，父块提升生成质量；代价是 metadata 体积上升，后续可演进为 parent_chunk 表。
4. pgvector 调优怎么讲：同一 10 万数据集下对比 HNSW/IVFFlat 的构建时间和 `EXPLAIN ANALYZE`；本次发现 IVFFlat 低 probes 会退化为 Seq Scan，因此默认选择 HNSW。
