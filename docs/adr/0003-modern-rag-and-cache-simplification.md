# ADR-0003：现代 RAG 链路与两层缓存

## 状态

Accepted

## 背景

原链路以向量 TopK + prompt 拼装为主，并存在 L0 本地缓存、L2 检索缓存、L3 文档块缓存等多层缓存。该设计难以证明质量提升，也会增加文档更新后的缓存一致性风险。

## 决策

1. 检索质量：保留已有 pgvector + PostgreSQL 全文检索 + RRF 融合，并补充可配置 Query Rewrite / HyDE。
2. 二阶段排序：新增外部 rerank 接口，默认关闭；启用后对融合候选调用 rerank API，失败回退本地排序。
3. 分块策略：新增父子分块，子块用于检索，命中后将父块上下文喂给 LLM。
4. 缓存策略：保留 L1 语义缓存与 L2 Redis 检索结果缓存；删除 L0 Caffeine 本地缓存和 L3 chunk 文档块缓存。

## 影响

- RAG 链路从“向量 TopK”升级为 query rewrite → hybrid search → rerank → parent context prompt。
- 检索缓存 key 继续包含 pipeline version 与 retrieval config hash。
- 文档更新只需按 `kb_id` 清理 L1/L2，避免 chunk 内容缓存过期导致旧内容泄露。
- 外部 rewrite/rerank 默认关闭，避免本地测试依赖云 API。
