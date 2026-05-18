\set ON_ERROR_STOP on
\if :{?benchmark_tenant_id}
\else
\set benchmark_tenant_id 91001
\endif
\if :{?benchmark_user_id}
\else
\set benchmark_user_id 91001
\endif
\if :{?benchmark_kb_id}
\else
\set benchmark_kb_id 91001
\endif
\if :{?benchmark_doc_id}
\else
\set benchmark_doc_id 91001
\endif
\if :{?benchmark_conversation_id}
\else
\set benchmark_conversation_id 91001
\endif
\if :{?benchmark_rows}
\else
\set benchmark_rows 5000
\endif

-- Benchmark fixture for /api/v1/chat/stream.
-- Auth is JWT-only in JwtAuthFilter, so password_hash is not used by the benchmark token.
-- Use benchmarks/scripts/generate-benchmark-jwt.mjs with matching ids.
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
VALUES (:benchmark_tenant_id, 'chat-stream-benchmark-tenant', 1)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();

INSERT INTO sys_user (id, username, password_hash, email, role, tenant_id, status)
VALUES (:benchmark_user_id, 'benchmark-user', 'jwt-only-benchmark-user', 'benchmark@example.com', 'ADMIN', :benchmark_tenant_id, 1)
ON CONFLICT (id) DO UPDATE
SET role = EXCLUDED.role, tenant_id = EXCLUDED.tenant_id, status = EXCLUDED.status, updated_at = NOW();

INSERT INTO knowledge_base (id, name, description, tenant_id, embedding_model, created_by, retrieval_config, chunk_strategy, doc_count, status)
VALUES (
    :benchmark_kb_id,
    'chat-stream-benchmark-kb',
    'Fixture used by k6 SSE benchmark with mock or real OpenAI-compatible APIs.',
    :benchmark_tenant_id,
    'benchmark-mock-compatible-1536',
    :benchmark_user_id,
    '{"preset":"GENERAL_QA","hybridEnabled":true,"rerankEnabled":true,"denseTopK":20,"sparseTopK":20,"fusionTopK":15,"finalTopK":5,"denseWeight":0.55,"sparseWeight":0.45}'::jsonb,
    '{"version":2,"type":"STRUCTURE_AWARE","size":800,"overlap":120,"minSize":200,"normalizeWhitespace":true,"parentChildEnabled":true,"parentSize":1800,"childSize":420,"childOverlap":80}'::jsonb,
    1,
    'ACTIVE'
)
ON CONFLICT (id) DO UPDATE
SET description = EXCLUDED.description,
    tenant_id = EXCLUDED.tenant_id,
    retrieval_config = EXCLUDED.retrieval_config,
    chunk_strategy = EXCLUDED.chunk_strategy,
    updated_at = NOW();

INSERT INTO document (id, kb_id, title, file_key, file_type, file_size, content_hash, parse_status, chunk_count, created_by)
VALUES (
    :benchmark_doc_id,
    :benchmark_kb_id,
    'chat-stream-benchmark-corpus',
    'benchmark/chat-stream.txt',
    'txt',
    1,
    md5(:benchmark_kb_id::text || ':' || :benchmark_rows::text || ':chat-stream'),
    'COMPLETED',
    :benchmark_rows,
    :benchmark_user_id
)
ON CONFLICT (id) DO UPDATE
SET kb_id = EXCLUDED.kb_id,
    chunk_count = EXCLUDED.chunk_count,
    parse_status = EXCLUDED.parse_status,
    updated_at = NOW();

DELETE FROM chat_message WHERE conversation_id = :benchmark_conversation_id;
INSERT INTO conversation (id, user_id, kb_id, title, model, config)
VALUES (:benchmark_conversation_id, :benchmark_user_id, :benchmark_kb_id, 'chat stream benchmark', 'mock-chat', '{"temperature":0.1,"topK":5}'::jsonb)
ON CONFLICT (id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    kb_id = EXCLUDED.kb_id,
    title = EXCLUDED.title,
    model = EXCLUDED.model,
    config = EXCLUDED.config,
    updated_at = NOW();

DELETE FROM document_chunk WHERE kb_id = :benchmark_kb_id;

CREATE TEMP TABLE ib_chat_fixture_topic_vectors AS
SELECT topic, ib_fixture_vector(topic) AS embedding
FROM generate_series(0, 5) AS s(topic);

INSERT INTO document_chunk (doc_id, kb_id, chunk_index, content, lexical_content, token_count, embedding, lexical_vector, metadata)
SELECT
    :benchmark_doc_id AS doc_id,
    :benchmark_kb_id AS kb_id,
    g AS chunk_index,
    CASE g % 6
        WHEN 0 THEN 'Spring Boot 3 JWT SecurityContext tenant isolation RabbitMQ DLQ idempotency SETNX chunk ' || g
        WHEN 1 THEN 'PostgreSQL pgvector HNSW IVFFlat probes ef_search tsvector GIN RRF hybrid search chunk ' || g
        WHEN 2 THEN 'Java 17 CompletableFuture bounded thread pool backpressure JMH k6 p95 p99 chunk ' || g
        WHEN 3 THEN 'RAG query rewriting HyDE external rerank faithfulness answer relevance golden QA chunk ' || g
        WHEN 4 THEN 'Apache Tika MinIO OCR PDF streaming parser memory pressure retry chunk ' || g
        ELSE 'Redis semantic cache retrieval result cache kb_id invalidation sanity check chunk ' || g
    END AS content,
    lower(CASE g % 6
        WHEN 0 THEN 'spring boot jwt securitycontext tenant isolation rabbitmq dlq idempotency setnx chunk ' || g
        WHEN 1 THEN 'postgresql pgvector hnsw ivfflat probes ef_search tsvector gin rrf hybrid search chunk ' || g
        WHEN 2 THEN 'java 17 completablefuture bounded thread pool backpressure jmh k6 p95 p99 chunk ' || g
        WHEN 3 THEN 'rag query rewriting hyde external rerank faithfulness answer relevance golden qa chunk ' || g
        WHEN 4 THEN 'apache tika minio ocr pdf streaming parser memory pressure retry chunk ' || g
        ELSE 'redis semantic cache retrieval result cache kb_id invalidation sanity check chunk ' || g
    END) AS lexical_content,
    64 AS token_count,
    tv.embedding AS embedding,
    to_tsvector('simple', lower(CASE g % 6
        WHEN 0 THEN 'spring boot jwt securitycontext tenant isolation rabbitmq dlq idempotency setnx chunk ' || g
        WHEN 1 THEN 'postgresql pgvector hnsw ivfflat probes ef_search tsvector gin rrf hybrid search chunk ' || g
        WHEN 2 THEN 'java 17 completablefuture bounded thread pool backpressure jmh k6 p95 p99 chunk ' || g
        WHEN 3 THEN 'rag query rewriting hyde external rerank faithfulness answer relevance golden qa chunk ' || g
        WHEN 4 THEN 'apache tika minio ocr pdf streaming parser memory pressure retry chunk ' || g
        ELSE 'redis semantic cache retrieval result cache kb_id invalidation sanity check chunk ' || g
    END)) AS lexical_vector,
    jsonb_build_object(
        'source', 'chat-stream-benchmark-fixture',
        'fixtureRow', g,
        'parentContent', 'Parent context for fixture topic ' || (g % 6) || ': validates parent-child retrieval window for SSE benchmark.'
    ) AS metadata
FROM generate_series(1, :benchmark_rows) AS s(g)
JOIN ib_chat_fixture_topic_vectors tv ON tv.topic = g % 6;

ANALYZE document_chunk;

SELECT :benchmark_tenant_id AS tenant_id,
       :benchmark_user_id AS user_id,
       :benchmark_kb_id AS kb_id,
       :benchmark_conversation_id AS conversation_id,
       COUNT(*) AS generated_chunks
FROM document_chunk
WHERE kb_id = :benchmark_kb_id;
