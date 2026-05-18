# Raw benchmark results

保存规则：

- `pgvector-YYYYMMDD-HHMMSS.txt`：`pgvector-index-benchmark.sql` 原始输出。
- `pgvector-latency-YYYYMMDD-HHMMSS.txt`：`pgvector-latency-percentiles.sql` 多查询分位数输出。
- `realtext-generate-100k-YYYYMMDD-HHMMSS.txt`：`generate-realtext-pgvector-fixtures.mjs` 生成并导入 10 万 real-text chunks 的原始输出。
- `realtext-fixture-smoke-<chunks>-YYYYMMDD-HHMMSS.txt`：real-text fixture 脚本的小规模 smoke 输出。
- `versioned-evaluation-report-YYYYMMDD-HHMMSS.md`：`VersionedRetrievalEvaluationIT` 输出的 seeded 检索版本对比报告。
- `versioned-evaluation-metrics-YYYYMMDD-HHMMSS.json`：`VersionedRetrievalEvaluationIT` 输出的 seeded 检索版本对比原始指标。
- `real-api-evaluation-report-YYYYMMDD-HHMMSS.md`：`RealApiRetrievalEvaluationIT` 输出的真实 embedding / 可选 rewrite / 可选 rerank 版本对比报告。
- `real-api-evaluation-metrics-YYYYMMDD-HHMMSS.json`：`RealApiRetrievalEvaluationIT` 输出的真实 API 版本对比原始指标。
- `real-api-evaluation-metadata-YYYYMMDD-HHMMSS.md`：真实 API 评测的运行前提，包含模型、API base URL、rewrite/rerank/judge 开关、JDK/OS、PostgreSQL fixture 等；密钥只记录是否设置，不记录值。
- `k6-chat-stream-real-YYYYMMDD-HHMMSS.txt` / `k6-chat-stream-real-summary-YYYYMMDD-HHMMSS.json`：`run-real-chat-stream-k6.sh` 输出的真实端到端 SSE 压测结果。
- `k6-chat-stream-real-metadata-YYYYMMDD-HHMMSS.md`：真实 k6 压测的运行前提，包含并发、时长、base URL、模型/重写/rerank 环境变量捕获情况；密钥只记录是否设置，不记录值。
- `sse-smoke-mock-<chunks>-YYYYMMDD-HHMMSS.txt`：使用本地 mock API 的 SSE 冒烟输出，仅验证链路连通。
- `k6-chat-stream-YYYYMMDD-HHMMSS.txt`：k6 控制台输出。
- `k6-chat-stream-summary.json`：k6 summary export。

如果使用 `mock-openai-server.mjs`，文件名必须带 `mock`，例如：

- `k6-chat-stream-mock-10vu-5000chunks-YYYYMMDD-HHMMSS.txt`
- `k6-chat-stream-mock-summary.json`

mock 结果只能证明压测链路和应用内开销，不能作为真实 LLM/Embedding/Rerank 延迟或质量指标。
versioned evaluation 结果只能证明评测矩阵和检索链路回归断言，不代表真实 embedding / 真实 rerank API / 真实 query rewrite 质量。
real-api evaluation 可以作为质量指标来源，但必须同时记录 API vendor/model、数据集、PostgreSQL 版本、硬件和运行命令。

`benchmarks/scripts/verify-benchmark-artifacts.mjs` 不只检查文件名，也会对每类最新 raw result 做最低限度内容校验：

- 10 万 fixture 文件必须包含 100000 行导入/生成证据。
- pgvector latency 文件必须包含 P50/P95/P99 与 200 次采样场景。
- versioned evaluation 必须包含 baseline / hybrid / rerank / rewrite 四类场景，并标注 seeded deterministic。
- 真实 k6 文件必须包含 metadata、thresholds、失败率和流式延迟指标。
- 真实 API evaluation report 必须包含 metadata、baseline/hybrid 场景和 Recall@5/MRR。
- versioned / real-api / real-k6 结果必须按同一时间戳带齐 report/log、metrics/summary 与 metadata companion 文件，避免只复制单个结论文件；metrics/summary JSON 还必须能解析，并包含对应场景或 k6 指标键。

该校验是最终验收门禁的一部分，但不能替代人工确认供应商、模型、硬件、并发和原始命令。

未运行真实压测前，不在 README 或简历中写实测性能数字。
