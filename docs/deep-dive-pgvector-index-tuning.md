# Deep Dive：pgvector 索引调优（IVFFlat vs HNSW）

## 1. 问题描述

IntelliBase 的 RAG 检索要在 PostgreSQL/pgvector 中完成向量召回。如果只在简历里写“十万级向量几十毫秒”，但没有数据、脚本和 EXPLAIN 计划，面试时很容易被追问到无法自证。因此本次选择 **pgvector 索引调优** 作为深挖主题：在同一份 10 万条、1536 维向量数据上对比 HNSW、IVFFlat 和 PostgreSQL 全文 GIN 的构建成本与查询延迟。

目标不是追求某个绝对数字，而是沉淀一套可复现的决策过程：

1. 固定数据规模、维度、TopK 和 SQL；
2. 保存原始 `EXPLAIN (ANALYZE, BUFFERS)`；
3. 记录索引构建时间、查询执行时间、planner 是否使用索引；
4. 将结论写回默认索引与 README 的性能口径。

## 2. 方案对比

| 方案 | 优点 | 风险/代价 | 适用判断 |
|---|---|---|---|
| HNSW | 查询延迟稳定，召回通常较高；不需要训练阶段 | 构建时间和内存占用更高 | IntelliBase 默认在线检索索引 |
| IVFFlat | 构建更快，参数直观（`lists`/`probes`） | probes 太低会损召回，planner 可能不走索引；参数依赖数据分布 | 写入/重建成本优先时作为备选 |
| 顺序扫描 | 无索引维护成本 | 数据稍大即不可接受 | 只用于小数据或 EXPLAIN 对照 |
| GIN 全文索引 | 适合精确词、错误码、API 名称召回 | 不能替代语义检索；中文分词质量需单独优化 | Hybrid Search 的 sparse 分支 |

## 3. 数据与复现命令

脚本位置：

- `benchmarks/scripts/generate-100k-pgvector-fixtures.sql`
- `benchmarks/scripts/pgvector-index-benchmark.sql`
- 原始结果：`benchmarks/raw-results/`

复现命令：

```bash
docker compose up -d postgres

psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v fixture_kb_id=90001 \
  -v fixture_rows=100000 \
  -f benchmarks/scripts/generate-100k-pgvector-fixtures.sql \
  | tee benchmarks/raw-results/generate-100k-$(date +%Y%m%d-%H%M%S).txt

psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v fixture_kb_id=90001 \
  -f benchmarks/scripts/pgvector-index-benchmark.sql \
  | tee benchmarks/raw-results/pgvector-$(date +%Y%m%d-%H%M%S).txt

psql postgresql://postgres:postgres@localhost:5432/intellibase \
  -v fixture_kb_id=90001 \
  -v sample_runs=200 \
  -f benchmarks/scripts/pgvector-latency-percentiles.sql \
  | tee benchmarks/raw-results/pgvector-latency-$(date +%Y%m%d-%H%M%S).txt
```

> 若本机 5432 被系统 PostgreSQL 占用，可用 `docker exec -i intellibase-postgres-1 psql -U postgres -d intellibase ...` 执行同样 SQL。

## 4. 实测结果（2026-05-18）

运行环境：本机 Docker Compose PostgreSQL 16 + pgvector，`document_chunk.embedding vector(1536)`，`kb_id=90001`，100,000 rows，Top-20，单查询 `EXPLAIN (ANALYZE, BUFFERS)`。

### 4.1 数据生成

| 项目 | 结果 |
|---|---:|
| 生成 rows | 100,000 |
| 插入耗时 | 3.519 s |
| 默认 HNSW 索引构建 | 18.719 s |
| GIN 全文索引构建 | 323 ms |
| 脚本总耗时 | 23.393 s |

### 4.2 索引查询对比

| 场景 | 索引/参数 | 计划 | 构建耗时 | Execution Time |
|---|---|---|---:|---:|
| 默认向量检索 | HNSW `idx_chunk_embedding` | Index Scan | 18.190 s | 0.460 ms |
| HNSW 调参 | `hnsw.ef_search=40` | Index Scan | 21.036 s | 0.395 ms |
| HNSW 调参 | `hnsw.ef_search=100` | Index Scan | 同上 | 0.345 ms |
| IVFFlat | `lists=100, probes=5` | Seq Scan | 4.131 s | 619.557 ms |
| IVFFlat | `lists=100, probes=20` | Index Scan | 同上 | 97.434 ms |
| 全文召回 | GIN `lexical_vector` | Bitmap Index Scan + Bitmap Heap Scan | 已存在 | 21.136 ms |

### 4.3 多查询延迟分位数

200 次采样，HNSW 每次从同一知识库随机选择一个已有向量作为 query vector；GIN 使用固定关键词 `pgvector hnsw probes`。该结果只覆盖数据库检索，不包含 HTTP、Embedding、Rerank 或 LLM。

| 场景 | Samples | P50 | P95 | P99 | Avg | Max |
|---|---:|---:|---:|---:|---:|---:|
| HNSW Top-20 (`ef_search=40`) | 200 | 0.166 ms | 0.203 ms | 1.468 ms | 0.198 ms | 1.517 ms |
| GIN lexical Top-20 | 200 | 17.903 ms | 22.422 ms | 25.926 ms | 18.561 ms | 37.210 ms |

## 5. 踩坑记录

1. **IVFFlat 构建内存不足**：`maintenance_work_mem=64MB` 时创建 `lists=100` 的 IVFFlat 报错 `memory required is 65 MB`。最终脚本显式设置为 `65MB`。
2. **临时表 + 并行查询冲突**：用临时表保存 query vector 时，PostgreSQL 并行 worker 报 `cannot access temporary tables during a parallel operation`。脚本设置 `max_parallel_workers_per_gather=0` 保证复现稳定。
3. **IVFFlat 不一定被 planner 选择**：`probes=5` 场景 planner 退化为 Seq Scan，实际耗时约 620ms。结论是参数调优必须看 `EXPLAIN`，不能只看索引是否存在。
4. **HNSW 构建内存提示**：构建 HNSW 时 pgvector 提示 graph 超出 `maintenance_work_mem`，会拖慢构建。10 万数据可接受，但百万级需要单独记录构建内存和时间。

## 6. 最终方案

- 默认索引：`document_chunk.embedding` 使用 HNSW，保留 `idx_chunk_embedding`。
- Hybrid Search：向量 HNSW 负责语义召回，`lexical_vector` GIN 负责关键词/错误码/API 名称召回，应用层使用 RRF 融合。
- 参数策略：
  - HNSW：默认 `ef_search` 使用数据库默认值；在压测场景中再按 Recall@K 与 P95 延迟调高。
  - IVFFlat：暂不作为默认，仅保留 benchmark 脚本用于候选方案对比。
- README/简历口径：只写能追溯到 `benchmarks/raw-results/` 的数字，并标注数据规模、TopK、运行环境；端到端 SSE 性能未跑出前不写 P95/P99。

## 7. 数据验证与后续计划

已验证：

- 10 万向量 fixture 可一键生成；
- HNSW/IVFFlat/GIN 的原始 EXPLAIN 结果已保存；
- 默认 HNSW 索引在脚本结束后会恢复，避免影响后续开发。

待补充：

1. 使用真实领域文本和真实 embedding 替换确定性 fixture，评估召回质量；
2. 将索引参数变化接入 `docs/evaluation.md` 的 Recall@5/MRR 对比；
3. 跑 k6 SSE 端到端压测，将 LLM API、并发、硬件写入结果表。
