# pgvector 10 万向量基准结果（2026-05-18）

> 原始输出：
> - 数据生成：`generate-100k-20260518-233000.txt`
> - 索引对比：`pgvector-20260518-233030.txt`
> - 多查询分位数：`pgvector-latency-20260518-233100.txt`
>
> 运行环境：本机 Docker PostgreSQL 16 + pgvector，`document_chunk.embedding vector(1536)`，`kb_id=99001`，100,000 rows，单查询 Top-20，`EXPLAIN (ANALYZE, BUFFERS)`。该结果只用于索引选型与复现样例；端到端 RAG 延迟仍需 k6 + 真实 LLM/Embedding API 单独记录。

## 数据生成

| 项目 | 结果 |
|---|---:|
| 生成 rows | 100,000 |
| 插入耗时 | 4.719 s |
| 默认 HNSW 索引构建 | 15.084 s |
| GIN 全文索引构建 | 384 ms |
| 脚本总耗时 | 20.870 s（导入 + HNSW + GIN + ANALYZE） |

## 索引对比

| 场景 | 索引/参数 | 计划 | 构建耗时 | Execution Time |
|---|---|---|---:|---:|
| 初始向量检索 | HNSW `idx_chunk_embedding` | Index Scan | 15.084 s | 0.921 ms |
| HNSW 调参 | `hnsw.ef_search=40` | Index Scan | 20.132 s | 0.426 ms |
| HNSW 调参 | `hnsw.ef_search=100` | Index Scan | 同上 | 0.345 ms |
| IVFFlat | `lists=100, probes=5` | Seq Scan | 7.064 s | 740.196 ms |
| IVFFlat | `lists=100, probes=20` | Index Scan | 同上 | 150.876 ms |
| 全文召回 | GIN `lexical_vector` | Bitmap Index Scan + Bitmap Heap Scan | 已存在 | 25.865 ms（OR tsquery） |

## 多查询延迟分位数

200 次采样，HNSW 每次从同一知识库随机选择一个已有向量作为 query vector；GIN 使用固定关键词 `pgvector hnsw probes`。结果用于观察数据库检索长尾，不包含 HTTP、Embedding、Rerank 或 LLM。

| 场景 | Samples | P50 | P95 | P99 | Avg | Max |
|---|---:|---:|---:|---:|---:|---:|
| HNSW Top-20 (`ef_search=40`) | 200 | 0.189 ms | 0.290 ms | 1.039 ms | 0.218 ms | 2.222 ms |
| GIN lexical Top-20 | 200 | 18.204 ms | 24.994 ms | 29.200 ms | 19.438 ms | 35.106 ms |

## 踩坑记录

1. `maintenance_work_mem=64MB` 无法创建 IVFFlat lists=100，pgvector 报 `memory required is 65 MB`；脚本已调为 `65MB`。
2. 使用临时表存 query vector 时，PostgreSQL 并行 worker 会报 `cannot access temporary tables during a parallel operation`；脚本已设置 `max_parallel_workers_per_gather=0` 保证复现稳定。
3. IVFFlat `probes=5` 在该数据分布下被 planner 选择为 Seq Scan，说明“有 IVFFlat 索引”不等于“必然低延迟”；需要对真实数据/查询分布做 EXPLAIN 验证。
4. HNSW 构建时提示 graph 超出 `maintenance_work_mem`，构建会变慢；本次仍可接受，后续大规模测试需记录内存与构建时间。

## 当前决策

- 10 万级数据默认保留 HNSW：查询耗时稳定在亚毫秒级，虽然构建耗时高于 IVFFlat，但在线检索延迟更可控。
- IVFFlat 不作为默认配置：本次 `probes=20` 仍约 151ms，`probes=5` 甚至退化为 Seq Scan。后续只有在插入/重建成本优先且召回可接受时再作为备选。
- 数据库检索分位数可写入 README/简历，但必须标明“不包含 HTTP/Embedding/Rerank/LLM”。端到端问答性能仍不写入：还缺少 k6 对 `/api/v1/chat/stream` 的 P50/P95/P99 和真实 LLM API 前提。
