# Deep Dive：pgvector 索引调优（IVFFlat vs HNSW）

## 1. 问题描述

IntelliBase 的 RAG 检索要在 PostgreSQL/pgvector 中完成向量召回。如果只在简历里写“十万级向量几十毫秒”，但没有数据、脚本和 EXPLAIN 计划，面试时很容易被追问到无法自证。因此本次选择 **pgvector 索引调优** 作为深挖主题：在同一份 10 万条、1536 维向量数据上对比 HNSW、IVFFlat 和 PostgreSQL 全文 GIN 的构建成本与查询延迟。后续补充了 real-text fixture：从仓库真实代码/文档/SQL 切分并平铺到 10 万 chunks，避免性能故事只停留在人工短句语料。

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
- `benchmarks/scripts/generate-realtext-pgvector-fixtures.mjs`
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

REALTEXT_ROWS=100000 \
REALTEXT_KB_ID=92001 \
node benchmarks/scripts/generate-realtext-pgvector-fixtures.mjs \
  | psql postgresql://postgres:postgres@localhost:5432/intellibase \
  | tee benchmarks/raw-results/realtext-generate-100k-$(date +%Y%m%d-%H%M%S).txt

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

运行环境：本机 Docker PostgreSQL 16 + pgvector，`document_chunk.embedding vector(1536)`，`kb_id=99001`，100,000 rows，Top-20，单查询 `EXPLAIN (ANALYZE, BUFFERS)`。

### 4.1 数据生成

| 项目 | 结果 |
|---|---:|
| 生成 rows | 100,000 |
| 插入耗时 | 4.719 s |
| 默认 HNSW 索引构建 | 15.084 s |
| GIN 全文索引构建 | 384 ms |
| 脚本总耗时 | 20.870 s（导入 + HNSW + GIN + ANALYZE） |

补充 real-text fixture（`realtext-generate-100k-20260518-231500.txt`）：从 196 个仓库源码/文档文件切分出 708 个去重 chunk 文本，平铺导入 100,000 rows，导入耗时 57.040 s。该数据使用 deterministic fixture vector，只用于规模与索引延迟验证，不作为真实 embedding 语义质量证明。

### 4.2 索引查询对比

| 场景 | 索引/参数 | 计划 | 构建耗时 | Execution Time |
|---|---|---|---:|---:|
| 初始向量检索 | HNSW `idx_chunk_embedding` | Index Scan | 15.084 s | 0.921 ms |
| HNSW 调参 | `hnsw.ef_search=40` | Index Scan | 20.132 s | 0.426 ms |
| HNSW 调参 | `hnsw.ef_search=100` | Index Scan | 同上 | 0.345 ms |
| IVFFlat | `lists=100, probes=5` | Seq Scan | 7.064 s | 740.196 ms |
| IVFFlat | `lists=100, probes=20` | Index Scan | 同上 | 150.876 ms |
| 全文召回 | GIN `lexical_vector` | Bitmap Index Scan + Bitmap Heap Scan | 已存在 | 25.865 ms（OR tsquery） |

### 4.3 多查询延迟分位数

200 次采样，HNSW 每次从同一知识库随机选择一个已有向量作为 query vector；GIN 使用固定关键词 `pgvector hnsw probes`。该结果只覆盖数据库检索，不包含 HTTP、Embedding、Rerank 或 LLM。

| 场景 | Samples | P50 | P95 | P99 | Avg | Max |
|---|---:|---:|---:|---:|---:|---:|
| HNSW Top-20 (`ef_search=40`) | 200 | 0.189 ms | 0.290 ms | 1.039 ms | 0.218 ms | 2.222 ms |
| GIN lexical Top-20 | 200 | 18.204 ms | 24.994 ms | 29.200 ms | 19.438 ms | 35.106 ms |

## 5. 踩坑记录

1. **IVFFlat 构建内存不足**：`maintenance_work_mem=64MB` 时创建 `lists=100` 的 IVFFlat 报错 `memory required is 65 MB`。最终脚本显式设置为 `65MB`。
2. **临时表 + 并行查询冲突**：用临时表保存 query vector 时，PostgreSQL 并行 worker 报 `cannot access temporary tables during a parallel operation`。脚本设置 `max_parallel_workers_per_gather=0` 保证复现稳定。
3. **IVFFlat 不一定被 planner 选择**：`probes=5` 场景 planner 退化为 Seq Scan，实际耗时约 740ms。结论是参数调优必须看 `EXPLAIN`，不能只看索引是否存在。
4. **HNSW 构建内存提示**：构建 HNSW 时 pgvector 提示 graph 超出 `maintenance_work_mem`，会拖慢构建。10 万数据可接受，但百万级需要单独记录构建内存和时间。

## 6. 最终方案

- 默认索引：`document_chunk.embedding` 使用 HNSW，保留 `idx_chunk_embedding`。
- Hybrid Search：向量 HNSW 负责语义召回，`lexical_vector` GIN 负责关键词/错误码/API 名称召回，应用层使用 RRF 融合。
- 参数策略：
  - HNSW：默认 `ef_search` 使用数据库默认值；在压测场景中再按 Recall@K 与 P95 延迟调高。
  - IVFFlat：暂不作为默认，仅保留 benchmark 脚本用于候选方案对比。
- README/简历口径：只写能追溯到 `benchmarks/raw-results/` 的数字，并标注数据规模、TopK、运行环境；端到端 SSE 单独引用 k6 真实结果，不把 DB-only pgvector 延迟等同为接口延迟。

## 7. 数据验证与后续计划

已验证：

- 10 万向量 fixture 可一键生成；
- HNSW/IVFFlat/GIN 的原始 EXPLAIN 结果已保存；
- 默认 HNSW 索引在脚本结束后会恢复，避免影响后续开发。

已补充端到端验证：`k6-chat-stream-real-20260519-163217.txt` 在 5 VUs / 1m / 220 requests、DashScope-compatible `qwen3.6-plus` + `text-embedding-v4` + `qwen3-rerank`、seeded benchmark KB、query rewrite/HyDE 关闭前提下，流式延迟 P50/P95/P99 = 203.5ms/1.613s/2.627s。该结果用于接口层性能口径，不能反推出 pgvector 索引单项耗时。

待补充：

1. 使用真实领域文本和真实 embedding 替换确定性 fixture，评估 10 万真实语义向量质量；
2. 将索引参数变化接入 `docs/evaluation.md` 的 Recall@5/MRR 对比；
3. 在更高并发和更大真实 KB 上复跑 k6，记录供应商限流、Token 输出长度和成本。
