#!/usr/bin/env bash
set -euo pipefail

# Run k6 against a live IntelliBase /api/v1/chat/stream endpoint.
# This script does not start the Spring app; start it with real LLM/Embedding/Rerank
# configuration first, then provide AUTH_TOKEN and CONVERSATION_ID.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

: "${AUTH_TOKEN:?Set AUTH_TOKEN for the benchmark user}"
: "${CONVERSATION_ID:?Set CONVERSATION_ID to an existing conversation bound to a populated KB}"
: "${BASE_URL:=http://localhost:8080}"
: "${VUS:=5}"
: "${DURATION:=1m}"
: "${TIMEOUT:=120s}"

mkdir -p benchmarks/raw-results
ts="$(date +%Y%m%d-%H%M%S)"
summary="benchmarks/raw-results/k6-chat-stream-real-summary-${ts}.json"
log="benchmarks/raw-results/k6-chat-stream-real-${ts}.txt"
metadata="benchmarks/raw-results/k6-chat-stream-real-metadata-${ts}.md"

if command -v k6 >/dev/null 2>&1; then
  K6=(k6 run --summary-export "${summary}")
  k6_runner="local"
else
  if [[ "${BASE_URL}" == "http://localhost:"* || "${BASE_URL}" == "http://127.0.0.1:"* ]]; then
    BASE_URL="${BASE_URL/localhost/host.docker.internal}"
    BASE_URL="${BASE_URL/127.0.0.1/host.docker.internal}"
  fi
  K6=(docker run --rm -i \
    -e BASE_URL -e AUTH_TOKEN -e CONVERSATION_ID -e VUS -e DURATION -e TIMEOUT -e SLEEP_SECONDS \
    -v "${REPO_ROOT}:/work" -w /work grafana/k6 run --summary-export "${summary}")
  k6_runner="docker:grafana/k6"
fi

{
  echo "# Real SSE k6 Run Metadata"
  echo
  echo "| Field | Value |"
  echo "|---|---|"
  echo "| timestamp | ${ts} |"
  echo "| command | benchmarks/scripts/run-real-chat-stream-k6.sh |"
  echo "| os | $(uname -a | sed 's/|/ /g') |"
  echo "| k6_runner | ${k6_runner} |"
  echo "| base_url | ${BASE_URL} |"
  echo "| auth_token_set | yes |"
  echo "| conversation_id | ${CONVERSATION_ID} |"
  echo "| vus | ${VUS} |"
  echo "| duration | ${DURATION} |"
  echo "| timeout | ${TIMEOUT} |"
  echo "| sleep_seconds | ${SLEEP_SECONDS:-1} |"
  echo "| openai_base_url | ${OPENAI_BASE_URL:-not_captured_by_runner} |"
  echo "| llm_model | ${LLM_MODEL_NAME:-not_captured_by_runner} |"
  echo "| embedding_model | ${EMBEDDING_MODEL_NAME:-not_captured_by_runner} |"
  echo "| embedding_dimensions | ${EMBEDDING_DIMENSIONS:-not_captured_by_runner} |"
  echo "| query_rewrite_enabled | ${RAG_QUERY_REWRITE_ENABLED:-not_captured_by_runner} |"
  echo "| hyde_enabled | ${RAG_HYDE_ENABLED:-not_captured_by_runner} |"
  echo "| rerank_external_enabled | ${RAG_RERANK_EXTERNAL_ENABLED:-not_captured_by_runner} |"
  echo "| rerank_api_url_set | $(if [[ -n "${RAG_RERANK_API_URL:-}" ]]; then echo yes; else echo not_captured_by_runner; fi) |"
  echo "| rerank_model | ${RAG_RERANK_MODEL:-not_captured_by_runner} |"
  echo
  echo "> Secrets are intentionally redacted; this file records whether credentials were set, not their values."
} > "${metadata}"

cp "${metadata}" "${log}"
printf '\n# k6 output\n\n' >> "${log}"

BASE_URL="${BASE_URL}" AUTH_TOKEN="${AUTH_TOKEN}" CONVERSATION_ID="${CONVERSATION_ID}" \
VUS="${VUS}" DURATION="${DURATION}" TIMEOUT="${TIMEOUT}" SLEEP_SECONDS="${SLEEP_SECONDS:-1}" \
  "${K6[@]}" benchmarks/scripts/k6-chat-stream.js | tee -a "${log}"

echo "Wrote ${log}"
echo "Wrote ${summary}"
echo "Wrote ${metadata}"
