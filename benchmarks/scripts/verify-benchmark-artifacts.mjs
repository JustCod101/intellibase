#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', '..');
const rawDir = path.join(repoRoot, 'benchmarks', 'raw-results');
const strict = process.argv.includes('--strict');

const checks = [
  {
    name: '100k synthetic pgvector fixture generation',
    pattern: /^generate-100k-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'Proves >=100k vector-scale fixture can be generated.',
  },
  {
    name: '100k real-text fixture generation',
    pattern: /^realtext-generate-100k-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'Proves repo real-text chunks can be imported at 100k row scale; vectors may still be deterministic fixtures.',
  },
  {
    name: 'pgvector index comparison',
    pattern: /^pgvector-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'HNSW / IVFFlat / GIN EXPLAIN ANALYZE raw output.',
  },
  {
    name: 'pgvector percentile latency',
    pattern: /^pgvector-latency-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'P50/P95/P99 database retrieval latency raw output.',
  },
  {
    name: 'seeded versioned retrieval matrix',
    pattern: /^versioned-evaluation-report-\d{8}-\d{6}\.md$/,
    requiredForCompletion: true,
    note: 'Regression matrix for dense-only / hybrid / rerank / rewrite; seeded only.',
  },
  {
    name: 'mock SSE k6 smoke',
    pattern: /^k6-chat-stream-mock-.*\.txt$/,
    requiredForCompletion: true,
    note: 'Proves benchmark harness and SSE path work with mock model latency.',
  },
  {
    name: 'real API retrieval matrix',
    pattern: /^real-api-evaluation-report-\d{8}-\d{6}\.md$/,
    requiredForCompletion: true,
    note: 'Required before publishing real Recall@5/MRR/Hit Rate for embedding/rerank/rewrite.',
  },
  {
    name: 'real SSE k6 benchmark',
    pattern: /^k6-chat-stream-real-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'Required before publishing endpoint P50/P95/P99 with real LLM/Embedding/Rerank.',
  },
];

if (!fs.existsSync(rawDir)) {
  console.error(`raw-results directory not found: ${rawDir}`);
  process.exit(1);
}

const files = fs.readdirSync(rawDir).sort();
let missing = 0;
for (const check of checks) {
  const matches = files.filter((file) => check.pattern.test(file));
  const ok = matches.length > 0;
  if (!ok && check.requiredForCompletion) missing += 1;
  const marker = ok ? 'OK  ' : 'MISS';
  console.log(`${marker} ${check.name}`);
  console.log(`     ${check.note}`);
  if (matches.length) {
    const latest = matches[matches.length - 1];
    console.log(`     latest: benchmarks/raw-results/${latest}`);
  }
}

console.log('');
if (missing === 0) {
  console.log('All benchmark artifact classes are present. Still verify each file matches the claimed model/vendor/hardware before publishing numbers.');
} else {
  console.log(`${missing} required benchmark artifact class(es) missing.`);
  console.log('Run the missing real API / real k6 scripts before marking the overall RAG refactor goal complete.');
}

if (strict && missing > 0) {
  process.exit(1);
}
