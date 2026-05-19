#!/usr/bin/env bash
set -euo pipefail

# Guard public-facing docs from drifting back to unsupported benchmark claims.
# This is intentionally conservative: it only checks README/resume wording, not
# ADRs or deep-dive docs where old claims may be discussed as anti-patterns.

public_claim_files=(
  README.md
  docs/resume-snippet.md
)

for file in "${public_claim_files[@]}"; do
  if [[ ! -f "${file}" ]]; then
    echo "MISS ${file}: public claim file not found" >&2
    exit 1
  fi
done

banned_patterns=(
  '35% 命中率'
  '35%命中率'
  '3\.2s'
  '1\.1s'
  '十万级向量数十毫秒'
  '十万级.*数十毫秒'
)

for pattern in "${banned_patterns[@]}"; do
  if grep -En "${pattern}" "${public_claim_files[@]}"; then
    echo "Unsupported public benchmark claim matched pattern: ${pattern}" >&2
    exit 1
  fi
done

required_readme_patterns=(
  '所有性能数字必须能追溯到'
  'seeded 结果不作为真实线上质量 claim'
  '不包含 HTTP、真实 Embedding、真实 Rerank、真实 LLM'
  'SSE 真实端到端结果使用 seeded benchmark KB、query rewrite/HyDE 关闭、external rerank 开启'
)

for pattern in "${required_readme_patterns[@]}"; do
  if ! grep -Fq "${pattern}" README.md; then
    echo "README.md missing claim-hygiene disclaimer: ${pattern}" >&2
    exit 1
  fi
done

required_resume_patterns=(
  '当前可量化口径（不要夸大）'
  '不作为真实 embedding / 外部 rerank API 质量 claim'
  'deterministic fixture vector'
  '真实端到端在 5 VUs/1m/220 requests'
)

for pattern in "${required_resume_patterns[@]}"; do
  if ! grep -Fq "${pattern}" docs/resume-snippet.md; then
    echo "docs/resume-snippet.md missing claim-hygiene disclaimer: ${pattern}" >&2
    exit 1
  fi
done

echo "Claim hygiene check passed for README.md and docs/resume-snippet.md."
