# IntelliBase Real API Retrieval Evaluation

This report is generated with the configured real embedding API, and external rerank API. Record API vendor/model/hardware/concurrency beside any published metric.

| Version | Recall@5 | MRR | Hit Rate@5 | Faithfulness | Answer Relevance |
|---|---:|---:|---:|---:|---:|
| baseline-dense-only | 100.00% | 94.64% | 100.00% | 100.00% | 92.17% |
| hybrid-rrf | 100.00% | 98.33% | 100.00% | 100.00% | 97.00% |
| hybrid-local-rerank | 100.00% | 99.17% | 100.00% | 100.00% | 98.83% |
| hybrid-external-rerank | 100.00% | 98.06% | 100.00% | 100.00% | 98.33% |

# Real API Evaluation Run Metadata

| Field | Value |
|---|---|
| timestamp | 20260519-035801 |
| command | benchmarks/scripts/run-real-api-evaluation.sh |
| java_home | /Users/numbbot/Library/Java/JavaVirtualMachines/ms-17.0.18/Contents/Home |
| java_version | openjdk version "17.0.18" 2026-01-20 LTS |
| os | Darwin Mac-mini.local 25.5.0 Darwin Kernel Version 25.5.0: Mon Apr 27 20:41:26 PDT 2026; root:xnu-12377.121.6~2/RELEASE_ARM64_T8132 arm64 |
| datasource_url | jdbc:postgresql://127.0.0.1:55437/intellibase |
| postgres_container | intellibase-real-eval-postgres |
| postgres_started_by_script | true |
| postgres_kept_after_run | false |
| openai_base_url | https://dashscope.aliyuncs.com/compatible-mode/v1 |
| openai_api_key_set | yes |
| embedding_model | text-embedding-v4 |
| embedding_dimensions | 1536 |
| llm_model | qwen3.6-plus |
| query_rewrite_enabled | false |
| hyde_enabled | false |
| rerank_external_enabled | yes |
| rerank_external_config_enabled | false |
| rerank_api_url_set | yes |
| rerank_api_key_set | yes |
| rerank_model | qwen3-rerank |
| llm_judge_api_key_set | yes |
| llm_judge_base_url | https://dashscope.aliyuncs.com/compatible-mode/v1 |
| llm_judge_model | glm-5.1 |
| llm_judge_concurrency | 4 |

> Secrets are intentionally redacted; this file records whether keys were set, not their values.
