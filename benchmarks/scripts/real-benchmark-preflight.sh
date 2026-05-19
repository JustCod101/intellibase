#!/usr/bin/env bash
set -euo pipefail

# Preflight checks for the real IntelliBase benchmark runs.
#
# This script intentionally does not call external APIs or mutate data. It only
# checks whether the local shell has the required configuration to run the two
# real evidence-producing scripts:
#   - benchmarks/scripts/run-real-api-evaluation.sh
#   - benchmarks/scripts/run-real-chat-stream-k6.sh

mode="${1:-all}"
failures=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

load_env_defaults() {
  local file="$1"
  local line key value
  [[ -f "${file}" ]] || return 0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "${line}" || "${line}" == \#* ]] && continue
    [[ "${line}" == export\ * ]] && line="${line#export }"
    key="${line%%=*}"
    [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ -z "${!key+x}" ]]; then
      value="${line#*=}"
      value="${value%$'\r'}"
      if [[ ( "${value}" == \"*\" && "${value}" == *\" ) || ( "${value}" == \'*\' && "${value}" == *\' ) ]]; then
        value="${value:1:${#value}-2}"
      fi
      export "${key}=${value}"
    fi
  done < "${file}"
}

load_env_defaults .env
load_env_defaults .env.real-sse

usage() {
  cat <<'USAGE'
Usage: benchmarks/scripts/real-benchmark-preflight.sh [all|retrieval|sse]

Checks required environment for real benchmark runs without calling external
APIs. Secrets are never printed.

Modes:
  retrieval  Check real API retrieval matrix prerequisites.
  sse        Check real /api/v1/chat/stream k6 prerequisites.
  all        Check both groups. This is the default.
USAGE
}

mask_status() {
  local name="$1"
  if [[ -n "${!name:-}" ]]; then
    echo "set"
  else
    echo "missing"
  fi
}

require_env() {
  local name="$1"
  local purpose="$2"
  if [[ -z "${!name:-}" ]]; then
    echo "MISS ${name}: ${purpose}"
    failures=$((failures + 1))
  else
    echo "OK   ${name}: set"
  fi
}

optional_env() {
  local name="$1"
  local purpose="$2"
  echo "INFO ${name}: $(mask_status "${name}") (${purpose})"
}

check_retrieval() {
  echo
  echo "==> Real API retrieval matrix"
  require_env "OPENAI_API_KEY" "embedding/rewrite/judge provider credential"
  optional_env "OPENAI_BASE_URL" "defaults to https://api.openai.com/v1"
  optional_env "EMBEDDING_MODEL_NAME" "defaults to text-embedding-v4"
  optional_env "EMBEDDING_DIMENSIONS" "defaults to 1536 and must match vector(1536)"
  optional_env "LLM_MODEL_NAME" "defaults to gpt-4o-mini"
  optional_env "RAG_QUERY_REWRITE_ENABLED" "set true to verify query rewrite with a real LLM"
  optional_env "RAG_HYDE_ENABLED" "set true only if HyDE should be benchmarked"
  optional_env "RAG_RERANK_EXTERNAL_ENABLED" "set true to verify external rerank"
  optional_env "RAG_RERANK_API_URL" "required only when external rerank is enabled"
  optional_env "RAG_RERANK_API_KEY" "required only when external rerank is enabled"
  optional_env "RAG_RERANK_MODEL" "defaults to bge-reranker-v2-m3"
  optional_env "EVALUATION_LLM_JUDGE_API_KEY" "required only for real LLM-as-judge scoring"

  if [[ "${RAG_RERANK_EXTERNAL_ENABLED:-false}" == "true" ]]; then
    require_env "RAG_RERANK_API_URL" "external rerank endpoint"
    require_env "RAG_RERANK_API_KEY" "external rerank credential"
  fi

  if ! command -v docker >/dev/null 2>&1 && [[ "${REAL_EVAL_START_POSTGRES:-true}" == "true" ]]; then
    echo "MISS docker: required when REAL_EVAL_START_POSTGRES=true"
    failures=$((failures + 1))
  else
    echo "OK   docker/postgres: available or external PostgreSQL selected"
  fi
}

check_sse() {
  echo
  echo "==> Real SSE k6 benchmark"
  require_env "AUTH_TOKEN" "benchmark user bearer token; run prepare-real-sse-benchmark-env.sh or set manually"
  require_env "CONVERSATION_ID" "existing conversation bound to a populated KB; run prepare-real-sse-benchmark-env.sh or set manually"
  optional_env "BASE_URL" "defaults to http://localhost:8080"
  optional_env "VUS" "defaults to 5"
  optional_env "DURATION" "defaults to 1m"
  optional_env "TIMEOUT" "defaults to 120s"

  if command -v k6 >/dev/null 2>&1; then
    echo "OK   k6 runner: local k6"
  elif command -v docker >/dev/null 2>&1; then
    echo "OK   k6 runner: docker fallback grafana/k6"
  else
    echo "MISS k6 runner: install k6 or make docker available"
    failures=$((failures + 1))
  fi
}

case "${mode}" in
  all)
    check_retrieval
    check_sse
    ;;
  retrieval)
    check_retrieval
    ;;
  sse)
    check_sse
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

echo
if (( failures > 0 )); then
  echo "Preflight failed: ${failures} missing prerequisite(s)."
  exit 1
fi

echo "Preflight passed. Run the real benchmark scripts and then final-acceptance-gate.sh."
