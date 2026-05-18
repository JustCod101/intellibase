# ADR-0002：性能数据必须由可复现脚本生成

## 状态

Accepted

## 背景

项目原 README/简历容易出现“十万级向量数十毫秒”等无法复现的表述。RAG 系统性能受数据规模、索引类型、参数、硬件、并发、外部 API 延迟影响，必须保存原始脚本与结果。

## 备选方案

1. **只保留 README 中的结论数字**：展示简单，但不可复核。
2. **引入独立压测平台**：能力强，但对当前单体 Java 项目过重。
3. **仓库内维护 SQL/k6 脚本和 raw-results**：轻量，可本地复现，可逐步补真实结果。

## 决策

选择方案 3：新增 `benchmarks/`，包含 10 万向量 fixture SQL、pgvector HNSW/IVFFlat 参数对比 SQL、k6 端到端 SSE 压测脚本和原始结果目录。

## 影响

- 未在 `benchmarks/raw-results/` 留存原始输出的性能数字，不写入 README/简历。
- 10 万向量 fixture 用 deterministic vector 压测索引与数据库延迟；真实语义质量仍由 golden QA + embedding API 评测。
- pgvector 索引决策必须同时记录构建时间、查询延迟和召回指标，不能只按单次延迟拍板。
