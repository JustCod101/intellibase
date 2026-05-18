\set ON_ERROR_STOP on
\set fixture_kb_id :fixture_kb_id
\set fixture_rows :fixture_rows

-- 生成 >=10 万条 RAG/Java/PostgreSQL 技术文本与 deterministic 1536 维向量。
-- 说明：向量用于索引与延迟压测，不代表真实 embedding 语义质量；真实质量评测仍走 golden QA + embedding API。
-- 为避免 100000 * 1536 次函数计算拖慢造数，脚本只预计算 6 个主题向量，再复制到 10 万行。

CREATE EXTENSION IF NOT EXISTS vector;
\timing on

CREATE OR REPLACE FUNCTION ib_fixture_vector(seed bigint, dim int DEFAULT 1536)
RETURNS vector
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT ('[' || string_agg(
        to_char(((sin((seed + i) * 0.017) + cos((seed - i) * 0.013) + 2.0) / 4.0), 'FM0.000000'),
        ',' ORDER BY i
    ) || ']')::vector
    FROM generate_series(1, dim) AS s(i)
$$;

INSERT INTO sys_tenant (id, name, status)
VALUES (90001, 'benchmark-tenant', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_user (id, username, password_hash, email, role, tenant_id, status)
VALUES (90001, 'benchmark-user', 'benchmark', 'benchmark@example.com', 'ADMIN', 90001, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_base (id, name, description, tenant_id, embedding_model, created_by, retrieval_config)
VALUES (
    :fixture_kb_id,
    'benchmark-100k-kb',
    'Synthetic benchmark corpus with real Java/RAG/PostgreSQL domain snippets',
    90001,
    'benchmark-deterministic-vector',
    90001,
    '{"preset":"GENERAL_QA","hybridEnabled":true,"rerankEnabled":true,"denseTopK":20,"sparseTopK":20,"fusionTopK":15,"finalTopK":5,"denseWeight":0.55,"sparseWeight":0.45}'::jsonb
)
ON CONFLICT (id) DO UPDATE SET updated_at = NOW();

INSERT INTO document (id, kb_id, title, file_key, file_type, file_size, content_hash, parse_status, chunk_count, created_by)
VALUES (
    90001,
    :fixture_kb_id,
    'benchmark-corpus-100k',
    'benchmark/100k.txt',
    'txt',
    1,
    md5(:fixture_kb_id::text || ':' || :fixture_rows::text),
    'COMPLETED',
    :fixture_rows,
    90001
)
ON CONFLICT (id) DO UPDATE SET chunk_count = :fixture_rows, parse_status = 'COMPLETED', updated_at = NOW();

DELETE FROM document_chunk WHERE kb_id = :fixture_kb_id;

DROP INDEX IF EXISTS idx_chunk_embedding;
DROP INDEX IF EXISTS idx_chunk_lexical_vector;

CREATE TEMP TABLE ib_fixture_topic_vectors AS
SELECT topic, ib_fixture_vector(topic) AS embedding
FROM generate_series(0, 5) AS s(topic);

INSERT INTO document_chunk (doc_id, kb_id, chunk_index, content, lexical_content, token_count, embedding, lexical_vector, metadata)
SELECT
    90001 AS doc_id,
    :fixture_kb_id AS kb_id,
    g AS chunk_index,
    CASE g % 6
        WHEN 0 THEN 'Spring Boot 3 构造器注入 JWT SecurityContext RabbitMQ DLQ 幂等 SETNX 文档解析 chunk ' || g
        WHEN 1 THEN 'PostgreSQL pgvector HNSW IVFFlat probes ef_search tsvector GIN RRF hybrid search chunk ' || g
        WHEN 2 THEN 'Java 17 CompletableFuture thread pool bounded queue backpressure JMH k6 P95 P99 chunk ' || g
        WHEN 3 THEN 'RAG query rewriting HyDE rerank cross encoder faithfulness answer relevance golden QA chunk ' || g
        WHEN 4 THEN 'Apache Tika MinIO OCR PDF streaming parser memory pressure RabbitMQ retry chunk ' || g
        ELSE 'Redis semantic cache retrieval cache cache invalidation kb_id consistency sanity check chunk ' || g
    END AS content,
    lower(CASE g % 6
        WHEN 0 THEN 'spring boot constructor injection jwt securitycontext rabbitmq dlq idempotency setnx document parsing chunk ' || g
        WHEN 1 THEN 'postgresql pgvector hnsw ivfflat probes ef_search tsvector gin rrf hybrid search chunk ' || g
        WHEN 2 THEN 'java 17 completablefuture thread pool bounded queue backpressure jmh k6 p95 p99 chunk ' || g
        WHEN 3 THEN 'rag query rewriting hyde rerank cross encoder faithfulness answer relevance golden qa chunk ' || g
        WHEN 4 THEN 'apache tika minio ocr pdf streaming parser memory pressure rabbitmq retry chunk ' || g
        ELSE 'redis semantic cache retrieval cache invalidation kb_id consistency sanity check chunk ' || g
    END) AS lexical_content,
    64 AS token_count,
    tv.embedding AS embedding,
    to_tsvector('simple', lower(CASE g % 6
        WHEN 0 THEN 'spring boot constructor injection jwt securitycontext rabbitmq dlq idempotency setnx document parsing chunk ' || g
        WHEN 1 THEN 'postgresql pgvector hnsw ivfflat probes ef_search tsvector gin rrf hybrid search chunk ' || g
        WHEN 2 THEN 'java 17 completablefuture thread pool bounded queue backpressure jmh k6 p95 p99 chunk ' || g
        WHEN 3 THEN 'rag query rewriting hyde rerank cross encoder faithfulness answer relevance golden qa chunk ' || g
        WHEN 4 THEN 'apache tika minio ocr pdf streaming parser memory pressure rabbitmq retry chunk ' || g
        ELSE 'redis semantic cache retrieval cache invalidation kb_id consistency sanity check chunk ' || g
    END)) AS lexical_vector,
    jsonb_build_object('source', 'benchmark-fixture', 'fixtureRow', g) AS metadata
FROM generate_series(1, :fixture_rows) AS s(g)
JOIN ib_fixture_topic_vectors tv ON tv.topic = g % 6;

CREATE INDEX idx_chunk_embedding ON document_chunk
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_chunk_lexical_vector ON document_chunk USING gin (lexical_vector);

ANALYZE document_chunk;

SELECT :fixture_kb_id AS kb_id, COUNT(*) AS generated_chunks
FROM document_chunk
WHERE kb_id = :fixture_kb_id;
