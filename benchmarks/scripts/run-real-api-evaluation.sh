#!/usr/bin/env bash
set -euo pipefail

# Run the real-API retrieval quality matrix and persist raw reports.
# Requires a real embedding API key. Optional: RAG_QUERY_REWRITE_ENABLED=true and
# RAG_RERANK_API_URL/RAG_RERANK_API_KEY for rewrite/rerank scenarios.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

: "${OPENAI_API_KEY:?Set OPENAI_API_KEY to run real embedding evaluation}"
: "${OPENAI_BASE_URL:=https://api.openai.com/v1}"
: "${REAL_EVAL_PG_PORT:=55437}"
: "${REAL_EVAL_CONTAINER:=intellibase-real-eval-postgres}"
: "${REAL_EVAL_START_POSTGRES:=true}"
: "${REAL_EVAL_KEEP_POSTGRES:=false}"
: "${SPRING_DATASOURCE_URL:=jdbc:postgresql://127.0.0.1:${REAL_EVAL_PG_PORT}/intellibase}"

if [[ "${REAL_EVAL_START_POSTGRES}" == "true" ]]; then
  if ! docker ps --format '{{.Names}}' | grep -qx "${REAL_EVAL_CONTAINER}"; then
    docker rm -f "${REAL_EVAL_CONTAINER}" >/dev/null 2>&1 || true
    docker run -d --name "${REAL_EVAL_CONTAINER}" \
      -e POSTGRES_DB=intellibase -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
      -p "${REAL_EVAL_PG_PORT}:5432" \
      -v "${REPO_ROOT}/sql/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro" \
      pgvector/pgvector:pg16 >/dev/null
  fi
  ready=false
  for _ in {1..30}; do
    if docker exec "${REAL_EVAL_CONTAINER}" pg_isready -U postgres -d intellibase >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    echo "PostgreSQL container ${REAL_EVAL_CONTAINER} did not become ready" >&2
    docker logs "${REAL_EVAL_CONTAINER}" --tail 80 >&2 || true
    exit 1
  fi
fi

cleanup() {
  if [[ "${REAL_EVAL_START_POSTGRES}" == "true" && "${REAL_EVAL_KEEP_POSTGRES}" != "true" ]]; then
    docker rm -f "${REAL_EVAL_CONTAINER}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

mvn_args=(
  -pl intellibase-server
  -Dtest=RealApiRetrievalEvaluationIT
  -Devaluation.real-api.enabled=true
  -Dspring.datasource.url="${SPRING_DATASOURCE_URL}"
  -Dembedding.api-key="${OPENAI_API_KEY}"
  -Dembedding.base-url="${OPENAI_BASE_URL}"
  -Dllm.api-key="${OPENAI_API_KEY}"
  -Dllm.base-url="${OPENAI_BASE_URL}"
)

if [[ -n "${RAG_QUERY_REWRITE_ENABLED:-}" ]]; then
  mvn_args+=("-Drag.query-rewrite.enabled=${RAG_QUERY_REWRITE_ENABLED}")
fi
if [[ -n "${RAG_HYDE_ENABLED:-}" ]]; then
  mvn_args+=("-Drag.query-rewrite.hyde-enabled=${RAG_HYDE_ENABLED}")
fi
if [[ -n "${RAG_RERANK_EXTERNAL_ENABLED:-}" ]]; then
  mvn_args+=("-Drag.rerank.external-enabled=${RAG_RERANK_EXTERNAL_ENABLED}")
fi
if [[ -n "${RAG_RERANK_API_URL:-}" ]]; then
  mvn_args+=("-Drag.rerank.api-url=${RAG_RERANK_API_URL}")
fi
if [[ -n "${RAG_RERANK_API_KEY:-}" ]]; then
  mvn_args+=("-Drag.rerank.api-key=${RAG_RERANK_API_KEY}")
fi
if [[ -n "${RAG_RERANK_MODEL:-}" ]]; then
  mvn_args+=("-Drag.rerank.model=${RAG_RERANK_MODEL}")
fi

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17.0.18)}" mvn "${mvn_args[@]}" test

ts="$(date +%Y%m%d-%H%M%S)"
mkdir -p benchmarks/raw-results
cp intellibase-server/target/evaluation/real-api-comparison-report.md \
  "benchmarks/raw-results/real-api-evaluation-report-${ts}.md"
cp intellibase-server/target/evaluation/real-api-comparison-metrics.json \
  "benchmarks/raw-results/real-api-evaluation-metrics-${ts}.json"

echo "Wrote benchmarks/raw-results/real-api-evaluation-report-${ts}.md"
echo "Wrote benchmarks/raw-results/real-api-evaluation-metrics-${ts}.json"
