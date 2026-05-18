#!/usr/bin/env bash
set -euo pipefail

# Final local gate for the IntelliBase RAG refactor.
#
# This script intentionally fails until the required real API retrieval matrix
# and real SSE k6 benchmark artifacts exist in benchmarks/raw-results.
# It is a guardrail against treating seeded/mock results as final evidence.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

run() {
  echo
  echo "==> $*"
  "$@"
}

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17.0.18 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

echo "==> Java runtime"
"${JAVA_HOME:+${JAVA_HOME}/bin/}java" -version

golden_set="intellibase-server/src/test/resources/evaluation/golden_qa.jsonl"
golden_count="$(wc -l < "${golden_set}" | tr -d '[:space:]')"
echo "==> Golden QA count: ${golden_count}"
if (( golden_count < 50 || golden_count > 100 )); then
  echo "Golden QA count must be between 50 and 100: ${golden_set}" >&2
  exit 1
fi

run bash -n benchmarks/scripts/run-real-api-evaluation.sh
run bash -n benchmarks/scripts/run-real-chat-stream-k6.sh
run bash -n benchmarks/scripts/real-benchmark-preflight.sh
run bash -n benchmarks/scripts/check-claim-hygiene.sh
run bash -n benchmarks/scripts/final-acceptance-gate.sh
run node --check benchmarks/scripts/generate-benchmark-jwt.mjs
run node --check benchmarks/scripts/generate-realtext-pgvector-fixtures.mjs
run node --check benchmarks/scripts/k6-chat-stream.js
run node --check benchmarks/scripts/mock-openai-server.mjs
run node --check benchmarks/scripts/verify-benchmark-artifacts.mjs

run benchmarks/scripts/check-claim-hygiene.sh

run mvn -pl intellibase-server test

run node benchmarks/scripts/verify-benchmark-artifacts.mjs --strict

echo
echo "Final acceptance gate passed. Verify raw files' vendor/model/hardware metadata before publishing README or resume numbers."
