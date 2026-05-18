\set ON_ERROR_STOP on
\set fixture_kb_id :fixture_kb_id
\timing on
SET maintenance_work_mem = '65MB';
SET max_parallel_workers_per_gather = 0;

CREATE TEMP TABLE ib_bench_query AS
SELECT ib_fixture_vector(42) AS q;

-- 使用固定 query vector 对比 pgvector 查询延迟。
-- 注意：CREATE INDEX CONCURRENTLY 不能放在事务中；本脚本按顺序执行并输出每步 timing。

DROP INDEX IF EXISTS idx_bench_chunk_embedding_hnsw;
DROP INDEX IF EXISTS idx_bench_chunk_embedding_ivf_100;
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON document_chunk
USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

SELECT COUNT(*) AS chunks FROM document_chunk WHERE kb_id = :fixture_kb_id;

-- Baseline query against whatever index currently exists.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, 1 - (embedding <=> (SELECT q FROM ib_bench_query)) AS similarity
FROM document_chunk
WHERE kb_id = :fixture_kb_id
ORDER BY embedding <=> (SELECT q FROM ib_bench_query)
LIMIT 20;

DROP INDEX IF EXISTS idx_chunk_embedding;

-- HNSW 参数：适合高召回低延迟检索，但构建/内存成本更高。
CREATE INDEX idx_bench_chunk_embedding_hnsw ON document_chunk
USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
ANALYZE document_chunk;
SET hnsw.ef_search = 40;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, 1 - (embedding <=> (SELECT q FROM ib_bench_query)) AS similarity
FROM document_chunk
WHERE kb_id = :fixture_kb_id
ORDER BY embedding <=> (SELECT q FROM ib_bench_query)
LIMIT 20;
SET hnsw.ef_search = 100;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, 1 - (embedding <=> (SELECT q FROM ib_bench_query)) AS similarity
FROM document_chunk
WHERE kb_id = :fixture_kb_id
ORDER BY embedding <=> (SELECT q FROM ib_bench_query)
LIMIT 20;
DROP INDEX IF EXISTS idx_bench_chunk_embedding_hnsw;

-- IVFFlat 参数：lists/probes 需要随数据量调参；probes 越高通常召回越好但延迟越高。
CREATE INDEX idx_bench_chunk_embedding_ivf_100 ON document_chunk
USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
ANALYZE document_chunk;
SET ivfflat.probes = 5;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, 1 - (embedding <=> (SELECT q FROM ib_bench_query)) AS similarity
FROM document_chunk
WHERE kb_id = :fixture_kb_id
ORDER BY embedding <=> (SELECT q FROM ib_bench_query)
LIMIT 20;
SET ivfflat.probes = 20;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, 1 - (embedding <=> (SELECT q FROM ib_bench_query)) AS similarity
FROM document_chunk
WHERE kb_id = :fixture_kb_id
ORDER BY embedding <=> (SELECT q FROM ib_bench_query)
LIMIT 20;
DROP INDEX IF EXISTS idx_bench_chunk_embedding_ivf_100;

-- Lexical side of hybrid search.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, ts_rank_cd(lexical_vector, plainto_tsquery('simple', 'pgvector hnsw probes')) AS lexical_score
FROM document_chunk
WHERE kb_id = :fixture_kb_id
  AND lexical_vector @@ plainto_tsquery('simple', 'pgvector hnsw probes')
ORDER BY lexical_score DESC, id DESC
LIMIT 20;

DROP INDEX IF EXISTS idx_bench_chunk_embedding_ivf_100;
CREATE INDEX idx_chunk_embedding ON document_chunk
USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
