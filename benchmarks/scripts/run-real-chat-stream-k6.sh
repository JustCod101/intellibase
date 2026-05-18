#!/usr/bin/env bash
set -euo pipefail

# Run k6 against a live IntelliBase /api/v1/chat/stream endpoint.
# This script does not start the Spring app; start it with real LLM/Embedding/Rerank
# configuration first, then provide AUTH_TOKEN and CONVERSATION_ID.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

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

if command -v k6 >/dev/null 2>&1; then
  K6=(k6 run --summary-export "${summary}")
else
  if [[ "${BASE_URL}" == "http://localhost:"* || "${BASE_URL}" == "http://127.0.0.1:"* ]]; then
    BASE_URL="${BASE_URL/localhost/host.docker.internal}"
    BASE_URL="${BASE_URL/127.0.0.1/host.docker.internal}"
  fi
  K6=(docker run --rm -i \
    -e BASE_URL -e AUTH_TOKEN -e CONVERSATION_ID -e VUS -e DURATION -e TIMEOUT -e SLEEP_SECONDS \
    -v "${REPO_ROOT}:/work" -w /work grafana/k6 run --summary-export "${summary}")
fi

BASE_URL="${BASE_URL}" AUTH_TOKEN="${AUTH_TOKEN}" CONVERSATION_ID="${CONVERSATION_ID}" \
VUS="${VUS}" DURATION="${DURATION}" TIMEOUT="${TIMEOUT}" SLEEP_SECONDS="${SLEEP_SECONDS:-1}" \
  "${K6[@]}" benchmarks/scripts/k6-chat-stream.js | tee "${log}"

echo "Wrote ${log}"
echo "Wrote ${summary}"
