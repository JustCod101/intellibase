# ADR-0001：RAG 重构采用评测先行

## 状态

Accepted

## 背景

IntelliBase 的重构目标是让 RAG 链路从基础 TopK 检索升级为现代 RAG。若直接实现 hybrid、rerank、query rewrite，容易出现“局部看起来更好、整体不可证明”的问题，也无法支撑简历中的量化指标。

## 备选方案

1. **先改 RAG，再人工抽样观察效果**：实现快，但结论不可复现。
2. **先接完整线上评测平台**：能力完整，但会引入额外系统复杂度，不符合 Java 单体约束。
3. **先做 JUnit 离线评测骨架 + JSONL golden set**：轻量、可版本化、可在 CI/本地一键运行，后续可替换真实检索结果。

## 决策

选择方案 3：先用 Java + JUnit 实现离线评测，固定 60 条 golden QA，输出 Recall@5、MRR、Hit Rate，并预留 RAGAS 风格 answer judge 接口。

## 影响

- 所有后续 RAG 改造必须先保存 run result，再跑同一套指标。
- 评测数据使用 JSONL，便于 review、diff 和后续扩充到 100 条。
- CI 默认使用确定性 heuristic judge；真实 LLM-as-judge 后续通过 OpenAI-compatible HTTP 接入。
