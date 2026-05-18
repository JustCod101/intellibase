-- ============================================================
-- IntelliBase 数据库初始化脚本
-- PostgreSQL 16 + pgvector
-- ============================================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 1. 租户管理
-- ============================================================

CREATE TABLE sys_tenant (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    status      SMALLINT NOT NULL DEFAULT 1,          -- 1=启用 0=禁用
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 2. 用户与权限体系
-- ============================================================

CREATE TABLE sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password_hash   VARCHAR(256) NOT NULL,
    email           VARCHAR(128),
    role            VARCHAR(32) NOT NULL DEFAULT 'USER',  -- ADMIN / USER / VIEWER
    tenant_id       BIGINT NOT NULL,                      -- 多租户隔离
    status          SMALLINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_tenant ON sys_user(tenant_id);
CREATE INDEX idx_user_username ON sys_user(username);

-- ============================================================
-- 2. 知识库管理
-- ============================================================

CREATE TABLE knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    tenant_id       BIGINT NOT NULL,
    embedding_model VARCHAR(64) NOT NULL DEFAULT 'text-embedding-3-small',
    chunk_strategy  JSONB NOT NULL DEFAULT '{"version":2,"type":"STRUCTURE_AWARE","size":800,"overlap":120,"minSize":200,"normalizeWhitespace":true,"parentChildEnabled":false,"parentSize":1800,"childSize":420,"childOverlap":80}',
    retrieval_config JSONB NOT NULL DEFAULT '{"preset":"GENERAL_QA","hybridEnabled":true,"rerankEnabled":true,"denseTopK":20,"sparseTopK":20,"fusionTopK":15,"finalTopK":5,"denseWeight":0.55,"sparseWeight":0.45}',
    doc_count       INT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT REFERENCES sys_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kb_tenant ON knowledge_base(tenant_id);

-- ============================================================
-- 3. 文档管理
-- ============================================================

CREATE TABLE document (
    id              BIGSERIAL PRIMARY KEY,
    kb_id           BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    title           VARCHAR(256) NOT NULL,
    file_key        VARCHAR(512) NOT NULL,          -- MinIO 对象Key
    file_type       VARCHAR(16) NOT NULL,           -- pdf, docx, md, txt
    file_size       BIGINT NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,           -- SHA-256 去重
    parse_status    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- PENDING -> PARSING -> EMBEDDING -> COMPLETED -> FAILED
    chunk_count     INT DEFAULT 0,
    metadata        JSONB,                          -- 自定义元数据（作者、版本等）
    created_by      BIGINT REFERENCES sys_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_kb ON document(kb_id);
CREATE INDEX idx_doc_hash ON document(content_hash);     -- 去重查询
CREATE INDEX idx_doc_status ON document(parse_status);   -- 任务调度

-- ============================================================
-- 4. 文档分块与向量存储（核心表）
-- ============================================================

CREATE TABLE document_chunk (
    id              BIGSERIAL PRIMARY KEY,
    doc_id          BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id           BIGINT NOT NULL,
    chunk_index     INT NOT NULL,                   -- 块序号
    content         TEXT NOT NULL,                   -- 原始文本
    lexical_content TEXT,                            -- 预分词后的检索文本
    token_count     INT NOT NULL,
    embedding       vector(1536),                   -- OpenAI ada-002: 1536维
    lexical_vector  TSVECTOR,                       -- PostgreSQL FTS 词项向量
    metadata        JSONB,                          -- 页码、标题层级等
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 向量索引：HNSW (任意数据量都可靠，召回率高于 IVFFlat)
CREATE INDEX idx_chunk_embedding ON document_chunk
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- 唯一约束：防止消息重复投递导致同一文档的同一分块被重复写入
CREATE UNIQUE INDEX uk_chunk_doc_index ON document_chunk(doc_id, chunk_index);

-- 复合索引：先按知识库过滤再做向量检索（大幅提升多租户场景性能）
CREATE INDEX idx_chunk_kb ON document_chunk(kb_id);
CREATE INDEX idx_chunk_doc ON document_chunk(doc_id);
CREATE INDEX idx_chunk_lexical_vector ON document_chunk USING gin (lexical_vector);

-- 一次性回填示例：为历史数据补齐 retrieval_config / lexical 索引字段
UPDATE knowledge_base
SET retrieval_config = COALESCE(
    retrieval_config,
    '{"preset":"GENERAL_QA","hybridEnabled":true,"rerankEnabled":true,"denseTopK":20,"sparseTopK":20,"fusionTopK":15,"finalTopK":5,"denseWeight":0.55,"sparseWeight":0.45}'::jsonb
);

UPDATE document_chunk
SET lexical_content = COALESCE(
        lexical_content,
        lower(
            regexp_replace(
                regexp_replace(content, '[./:-]+', '_', 'g'),
                '\s+',
                ' ',
                'g'
            )
        )
    ),
    lexical_vector = COALESCE(
        lexical_vector,
        to_tsvector(
            'simple',
            COALESCE(
                lexical_content,
                lower(
                    regexp_replace(
                        regexp_replace(content, '[./:-]+', '_', 'g'),
                        '\s+',
                        ' ',
                        'g'
                    )
                )
            )
        )
    )
WHERE lexical_content IS NULL OR lexical_vector IS NULL;

-- ============================================================
-- 5. 对话管理
-- ============================================================

CREATE TABLE conversation (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES sys_user(id),
    kb_id           BIGINT REFERENCES knowledge_base(id),
    title           VARCHAR(256),
    model           VARCHAR(64) NOT NULL DEFAULT 'gpt-4o',
    config          JSONB DEFAULT '{"temperature":0.7,"topK":5}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,            -- user / assistant / system
    content         TEXT NOT NULL,
    token_usage     JSONB,                           -- {prompt_tokens, completion_tokens}
    sources         JSONB,                           -- 引用来源 [{chunk_id, score, snippet}]
    latency_ms      INT,                             -- 响应耗时
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_msg_conv ON chat_message(conversation_id, created_at);

-- ============================================================
-- 6. 语义缓存表（可选：也可纯用Redis）
-- ============================================================

CREATE TABLE semantic_cache (
    id              BIGSERIAL PRIMARY KEY,
    kb_id           BIGINT NOT NULL,
    query_embedding vector(1536),
    query_text      TEXT NOT NULL,
    response_text   TEXT NOT NULL,
    hit_count       INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ
);

CREATE INDEX idx_cache_embedding ON semantic_cache
    USING hnsw (query_embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
