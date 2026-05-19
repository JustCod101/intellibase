#!/usr/bin/env bash
set -euo pipefail

# Prepare a benchmark user, populated KB, conversation, and JWT for the real SSE k6 runner.
# This script does not start the Spring app. It seeds the PostgreSQL database used by a
# running IntelliBase instance, then writes AUTH_TOKEN/CONVERSATION_ID exports to a local file.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

: "${BENCHMARK_USER_ID:=91001}"
: "${BENCHMARK_TENANT_ID:=${BENCHMARK_USER_ID}}"
: "${BENCHMARK_KB_ID:=91001}"
: "${BENCHMARK_DOC_ID:=91001}"
: "${BENCHMARK_CONVERSATION_ID:=91001}"
: "${BENCHMARK_USERNAME:=benchmark-user-${BENCHMARK_USER_ID}}"
: "${BENCHMARK_OUTPUT:=.env.real-sse}"
: "${EMBEDDING_MODEL_NAME:=text-embedding-v4}"
: "${LLM_MODEL_NAME:=gpt-4o-mini}"
: "${JWT_TTL_SECONDS:=86400}"

vector="$(node - <<'NODE'
const values = Array.from({ length: 1536 }, (_, i) => (((i % 31) + 1) / 1000).toFixed(6));
console.log(`[${values.join(',')}]`);
NODE
)"

sql="$(cat <<SQL
BEGIN;

INSERT INTO sys_tenant (id, name, status)
VALUES (${BENCHMARK_TENANT_ID}, 'benchmark-tenant', 1)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();

INSERT INTO sys_user (id, username, password_hash, email, role, tenant_id, status)
VALUES (${BENCHMARK_USER_ID}, '${BENCHMARK_USERNAME}', 'benchmark-password-hash-not-used', 'benchmark@example.local', 'ADMIN', ${BENCHMARK_TENANT_ID}, 1)
ON CONFLICT (id) DO UPDATE SET
  username = EXCLUDED.username,
  role = EXCLUDED.role,
  tenant_id = EXCLUDED.tenant_id,
  status = EXCLUDED.status,
  updated_at = NOW();

INSERT INTO knowledge_base (id, name, description, tenant_id, embedding_model, retrieval_config, doc_count, status, created_by)
VALUES (
  ${BENCHMARK_KB_ID},
  'real-sse-benchmark-kb',
  'Seeded benchmark KB for real /api/v1/chat/stream k6 runs',
  ${BENCHMARK_TENANT_ID},
  '${EMBEDDING_MODEL_NAME}',
  '{"preset":"GENERAL_QA","hybridEnabled":true,"rerankEnabled":true,"denseTopK":20,"sparseTopK":20,"fusionTopK":15,"finalTopK":5,"denseWeight":0.55,"sparseWeight":0.45}'::jsonb,
  1,
  'ACTIVE',
  ${BENCHMARK_USER_ID}
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  tenant_id = EXCLUDED.tenant_id,
  embedding_model = EXCLUDED.embedding_model,
  retrieval_config = EXCLUDED.retrieval_config,
  doc_count = EXCLUDED.doc_count,
  status = EXCLUDED.status,
  created_by = EXCLUDED.created_by,
  updated_at = NOW();

INSERT INTO document (id, kb_id, title, file_key, file_type, file_size, content_hash, parse_status, chunk_count, created_by)
VALUES (${BENCHMARK_DOC_ID}, ${BENCHMARK_KB_ID}, 'real-sse-benchmark.md', 'benchmarks/real-sse-benchmark.md', 'md', 1, 'real-sse-benchmark-seed', 'COMPLETED', 5, ${BENCHMARK_USER_ID})
ON CONFLICT (id) DO UPDATE SET
  kb_id = EXCLUDED.kb_id,
  title = EXCLUDED.title,
  parse_status = EXCLUDED.parse_status,
  chunk_count = EXCLUDED.chunk_count,
  updated_at = NOW();

DELETE FROM document_chunk WHERE doc_id = ${BENCHMARK_DOC_ID};

INSERT INTO document_chunk (doc_id, kb_id, chunk_index, content, lexical_content, token_count, embedding, lexical_vector, metadata)
VALUES
(${BENCHMARK_DOC_ID}, ${BENCHMARK_KB_ID}, 0,
 'pgvector HNSW 和 IVFFlat 的核心区别：HNSW 适合高召回低延迟近似检索，构建和内存成本更高；IVFFlat 依赖 lists 和 probes 参数，数据量较小时召回和延迟波动更明显。',
 'pgvector hnsw ivfflat 核心 区别 hnsw 高 召回 低 延迟 近似 检索 ivfflat lists probes 参数', 80, '${vector}'::vector,
 to_tsvector('simple', 'pgvector hnsw ivfflat 核心 区别 hnsw 高 召回 低 延迟 近似 检索 ivfflat lists probes 参数'), '{"source":"real_sse_seed"}'::jsonb),
(${BENCHMARK_DOC_ID}, ${BENCHMARK_KB_ID}, 1,
 'RabbitMQ 消费者幂等处理通常使用业务唯一键、Redis SETNX 和数据库唯一约束双保险；失败消息进入 DLQ 后按指数退避重投，避免无限重试冲垮系统。',
 'rabbitmq 消费者 幂等 redis setnx 数据库 唯一 约束 dlq 指数 退避 重投', 82, '${vector}'::vector,
 to_tsvector('simple', 'rabbitmq 消费者 幂等 redis setnx 数据库 唯一 约束 dlq 指数 退避 重投'), '{"source":"real_sse_seed"}'::jsonb),
(${BENCHMARK_DOC_ID}, ${BENCHMARK_KB_ID}, 2,
 'RRF 融合排序适合混合检索，因为它只依赖各召回通道内部排名，不要求向量相似度和全文相关性分数在同一尺度上可比。',
 'rrf 融合 排序 混合 检索 向量 全文 相关性 排名 reciprocal rank fusion', 72, '${vector}'::vector,
 to_tsvector('simple', 'rrf 融合 排序 混合 检索 向量 全文 相关性 排名 reciprocal rank fusion'), '{"source":"real_sse_seed"}'::jsonb),
(${BENCHMARK_DOC_ID}, ${BENCHMARK_KB_ID}, 3,
 'Java 线程池不建议使用无界队列，因为请求堆积会隐藏背压信号，导致内存持续增长和响应时间不可控；应设置有界队列、拒绝策略和监控。',
 'java 线程池 无界 队列 背压 内存 响应 时间 有界 队列 拒绝 策略 监控', 78, '${vector}'::vector,
 to_tsvector('simple', 'java 线程池 无界 队列 背压 内存 响应 时间 有界 队列 拒绝 策略 监控'), '{"source":"real_sse_seed"}'::jsonb),
(${BENCHMARK_DOC_ID}, ${BENCHMARK_KB_ID}, 4,
 '父子分块能提升 RAG 质量：子块保持较小粒度用于精准检索，命中后回填父块或上下文窗口给 LLM，兼顾召回精度与生成上下文完整性。',
 '父子 分块 rag 子块 精准 检索 父块 上下文 窗口 llm 召回 精度 生成 完整性', 76, '${vector}'::vector,
 to_tsvector('simple', '父子 分块 rag 子块 精准 检索 父块 上下文 窗口 llm 召回 精度 生成 完整性'), '{"source":"real_sse_seed"}'::jsonb);

INSERT INTO conversation (id, user_id, kb_id, title, model, config)
VALUES (${BENCHMARK_CONVERSATION_ID}, ${BENCHMARK_USER_ID}, ${BENCHMARK_KB_ID}, 'real-sse-benchmark-conversation', '${LLM_MODEL_NAME}', '{"temperature":0.7,"topK":5}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
  user_id = EXCLUDED.user_id,
  kb_id = EXCLUDED.kb_id,
  title = EXCLUDED.title,
  model = EXCLUDED.model,
  config = EXCLUDED.config,
  updated_at = NOW();

COMMIT;
SQL
)"

run_psql() {
  if [[ "${BENCHMARK_DB_MODE:-auto}" == "compose" || "${BENCHMARK_DB_MODE:-auto}" == "auto" ]]; then
    if command -v docker >/dev/null 2>&1 && docker compose ps postgres --status running >/dev/null 2>&1; then
      printf '%s\n' "${sql}" | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USERNAME:-postgres}" -d intellibase
      return 0
    fi
  fi

  if command -v psql >/dev/null 2>&1; then
    if [[ -n "${DATABASE_URL:-}" ]]; then
      printf '%s\n' "${sql}" | psql "${DATABASE_URL}" -v ON_ERROR_STOP=1
      return 0
    fi
    printf '%s\n' "${sql}" | PGPASSWORD="${DB_PASSWORD:-postgres}" psql \
      -h "${DB_HOST:-localhost}" \
      -p "${DB_PORT:-5432}" \
      -U "${DB_USERNAME:-postgres}" \
      -d "${DB_NAME:-intellibase}" \
      -v ON_ERROR_STOP=1
    return 0
  fi

  echo "No usable PostgreSQL runner found. Start docker compose postgres or install psql." >&2
  return 1
}

run_psql

AUTH_TOKEN="$(JWT_USER_ID="${BENCHMARK_USER_ID}" \
  JWT_USERNAME="${BENCHMARK_USERNAME}" \
  JWT_ROLE=ADMIN \
  JWT_TENANT_ID="${BENCHMARK_TENANT_ID}" \
  JWT_TTL_SECONDS="${JWT_TTL_SECONDS}" \
  node benchmarks/scripts/generate-benchmark-jwt.mjs)"

cat > "${BENCHMARK_OUTPUT}" <<OUT
# Generated by benchmarks/scripts/prepare-real-sse-benchmark-env.sh
# Source this file or copy these values into .env before running run-real-chat-stream-k6.sh.
AUTH_TOKEN=${AUTH_TOKEN}
CONVERSATION_ID=${BENCHMARK_CONVERSATION_ID}
BASE_URL=${BASE_URL:-http://localhost:8080}
OUT

cat <<MSG
Prepared real SSE benchmark prerequisites.
- user_id=${BENCHMARK_USER_ID}
- tenant_id=${BENCHMARK_TENANT_ID}
- kb_id=${BENCHMARK_KB_ID}
- conversation_id=${BENCHMARK_CONVERSATION_ID}
- output=${BENCHMARK_OUTPUT}

Next:
  set -a && source ${BENCHMARK_OUTPUT} && set +a
  benchmarks/scripts/real-benchmark-preflight.sh sse
  benchmarks/scripts/run-real-chat-stream-k6.sh
MSG
