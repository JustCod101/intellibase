# Raw benchmark results

保存规则：

- `pgvector-YYYYMMDD-HHMMSS.txt`：`pgvector-index-benchmark.sql` 原始输出。
- `pgvector-latency-YYYYMMDD-HHMMSS.txt`：`pgvector-latency-percentiles.sql` 多查询分位数输出。
- `sse-smoke-mock-<chunks>-YYYYMMDD-HHMMSS.txt`：使用本地 mock API 的 SSE 冒烟输出，仅验证链路连通。
- `k6-chat-stream-YYYYMMDD-HHMMSS.txt`：k6 控制台输出。
- `k6-chat-stream-summary.json`：k6 summary export。

如果使用 `mock-openai-server.mjs`，文件名必须带 `mock`，例如：

- `k6-chat-stream-mock-10vu-5000chunks-YYYYMMDD-HHMMSS.txt`
- `k6-chat-stream-mock-summary.json`

mock 结果只能证明压测链路和应用内开销，不能作为真实 LLM/Embedding/Rerank 延迟或质量指标。

未运行真实压测前，不在 README 或简历中写实测性能数字。
