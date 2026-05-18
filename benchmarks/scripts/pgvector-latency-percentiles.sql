-- 多查询 pgvector 延迟分位数基准。
-- 前置：先运行 generate-100k-pgvector-fixtures.sql。
-- 参数：
--   fixture_kb_id: 知识库 ID，默认 90001
--   sample_runs:   查询次数，默认 200

\if :{?fixture_kb_id}
\else
\set fixture_kb_id 90001
\endif

\if :{?sample_runs}
\else
\set sample_runs 200
\endif

\timing on
SET jit = off;
SET max_parallel_workers_per_gather = 0;
SET hnsw.ef_search = 40;
SELECT set_config('ib.fixture_kb_id', :'fixture_kb_id', false);
SELECT set_config('ib.sample_runs', :'sample_runs', false);

DROP TABLE IF EXISTS ib_latency_samples;
CREATE TEMP TABLE ib_latency_samples (
    scenario TEXT NOT NULL,
    sample_no INT NOT NULL,
    elapsed_ms NUMERIC NOT NULL
);

DO $$
DECLARE
    i INT;
    kb BIGINT := current_setting('ib.fixture_kb_id')::BIGINT;
    runs INT := current_setting('ib.sample_runs')::INT;
    q VECTOR(1536);
    started TIMESTAMPTZ;
BEGIN
    FOR i IN 1..runs LOOP
        SELECT embedding INTO q
        FROM document_chunk
        WHERE kb_id = kb
        ORDER BY random()
        LIMIT 1;

        started := clock_timestamp();
        PERFORM id
        FROM document_chunk
        WHERE kb_id = kb
        ORDER BY embedding <=> q
        LIMIT 20;
        INSERT INTO ib_latency_samples VALUES (
            'hnsw_top20_ef40', i,
            EXTRACT(EPOCH FROM (clock_timestamp() - started)) * 1000
        );
    END LOOP;

    FOR i IN 1..runs LOOP
        started := clock_timestamp();
        PERFORM id
        FROM document_chunk
        WHERE kb_id = kb
          AND lexical_vector @@ to_tsquery('simple', 'pgvector | hnsw | probes')
        ORDER BY ts_rank_cd(lexical_vector, to_tsquery('simple', 'pgvector | hnsw | probes')) DESC, id DESC
        LIMIT 20;
        INSERT INTO ib_latency_samples VALUES (
            'gin_lexical_top20', i,
            EXTRACT(EPOCH FROM (clock_timestamp() - started)) * 1000
        );
    END LOOP;
END $$;

SELECT
    scenario,
    COUNT(*) AS samples,
    ROUND(MIN(elapsed_ms), 3) AS min_ms,
    ROUND(percentile_disc(0.50) WITHIN GROUP (ORDER BY elapsed_ms), 3) AS p50_ms,
    ROUND(percentile_disc(0.95) WITHIN GROUP (ORDER BY elapsed_ms), 3) AS p95_ms,
    ROUND(percentile_disc(0.99) WITHIN GROUP (ORDER BY elapsed_ms), 3) AS p99_ms,
    ROUND(MAX(elapsed_ms), 3) AS max_ms,
    ROUND(AVG(elapsed_ms), 3) AS avg_ms
FROM ib_latency_samples
GROUP BY scenario
ORDER BY scenario;

SELECT scenario, sample_no, ROUND(elapsed_ms, 3) AS elapsed_ms
FROM ib_latency_samples
ORDER BY scenario, elapsed_ms DESC
LIMIT 20;
