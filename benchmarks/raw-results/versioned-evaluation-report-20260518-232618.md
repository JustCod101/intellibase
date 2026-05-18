# IntelliBase Versioned Retrieval Evaluation

Seeded deterministic corpus for pipeline comparison. This validates the version-comparison harness, not real embedding quality.

| Version | Recall@5 | MRR | Hit Rate@5 | Faithfulness | Answer Relevance |
|---|---:|---:|---:|---:|---:|
| baseline-dense-only | 0.00% | 0.00% | 0.00% | 100.00% | 1.67% |
| hybrid-rrf | 98.33% | 44.53% | 98.33% | 100.00% | 1.67% |
| hybrid-local-rerank | 98.33% | 95.28% | 98.33% | 100.00% | 95.42% |
| hybrid-rerank-query-rewrite | 100.00% | 95.28% | 100.00% | 100.00% | 93.75% |
