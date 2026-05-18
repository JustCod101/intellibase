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
    validate: (content) => [
      [/INSERT 0 100000/, 'must insert 100000 chunks'],
      [/generated_chunks[\s\S]*100000/, 'must report generated_chunks=100000'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
  {
    name: '100k real-text fixture generation',
    pattern: /^realtext-generate-100k-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'Proves repo real-text chunks can be imported at 100k row scale; vectors may still be deterministic fixtures.',
    validate: (content) => [
      [/COPY 100000/, 'must COPY 100000 staged rows'],
      [/INSERT 0 100000/, 'must insert 100000 chunks'],
      [/generated_chunks[\s\S]*source_files[\s\S]*distinct_chunk_texts[\s\S]*100000/, 'must report generated_chunks/source_files/distinct_chunk_texts'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
  {
    name: 'pgvector index comparison',
    pattern: /^pgvector-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'HNSW / IVFFlat / GIN EXPLAIN ANALYZE raw output.',
    validate: (content) => [
      [/chunks[\s\S]*100000/, 'must report 100000 benchmark chunks'],
      [/idx_bench_chunk_embedding_hnsw/, 'must include HNSW index plan'],
      [/idx_bench_chunk_embedding_ivf_100/, 'must include IVFFlat index plan'],
      [/Bitmap Index Scan on idx_chunk_lexical_vector/, 'must include GIN lexical plan'],
      [/Execution Time:/, 'must include EXPLAIN ANALYZE execution times'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
  {
    name: 'pgvector percentile latency',
    pattern: /^pgvector-latency-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'P50/P95/P99 database retrieval latency raw output.',
    validate: (content) => [
      [/p50_ms/, 'must include p50_ms column'],
      [/p95_ms/, 'must include p95_ms column'],
      [/p99_ms/, 'must include p99_ms column'],
      [/hnsw_top20_ef40\s+\|\s+200/, 'must include 200 HNSW samples'],
      [/gin_lexical_top20\s+\|\s+200/, 'must include 200 GIN samples'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
  {
    name: 'seeded versioned retrieval matrix',
    pattern: /^versioned-evaluation-report-\d{8}-\d{6}\.md$/,
    requiredForCompletion: true,
    note: 'Regression matrix for dense-only / hybrid / rerank / rewrite; seeded only.',
    validate: (content) => [
      [/baseline-dense-only/, 'must include baseline dense-only scenario'],
      [/hybrid-rrf/, 'must include hybrid RRF scenario'],
      [/hybrid-local-rerank/, 'must include local rerank scenario'],
      [/hybrid-rerank-query-rewrite/, 'must include query rewrite scenario'],
      [/Seeded deterministic corpus/, 'must label the report as seeded deterministic'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
  {
    name: 'mock SSE k6 smoke',
    pattern: /^k6-chat-stream-mock-.*\.txt$/,
    requiredForCompletion: true,
    note: 'Proves benchmark harness and SSE path work with mock model latency.',
    validate: (content) => [
      [/THRESHOLDS/, 'must include k6 thresholds'],
      [/http_req_failed[\s\S]*rate<0\.05/, 'must include http failure threshold'],
      [/rag_stream_latency[\s\S]*p\(95\)<120000/, 'must include stream latency threshold'],
      [/checks_succeeded[\s\S]*100\.00%/, 'must show successful checks'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
  {
    name: 'real API retrieval matrix',
    pattern: /^real-api-evaluation-report-\d{8}-\d{6}\.md$/,
    requiredForCompletion: true,
    note: 'Required before publishing real Recall@5/MRR/Hit Rate for embedding/rerank/rewrite.',
    validate: (content) => [
      [/Real API Retrieval Evaluation/, 'must be a real API evaluation report'],
      [/Real API Evaluation Run Metadata/, 'must include run metadata with model/vendor/config context'],
      [/baseline-dense-only/, 'must include baseline scenario'],
      [/hybrid-rrf/, 'must include hybrid scenario'],
      [/Recall@5/, 'must include Recall@5 metric'],
      [/MRR/, 'must include MRR metric'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
  {
    name: 'real SSE k6 benchmark',
    pattern: /^k6-chat-stream-real-\d{8}-\d{6}\.txt$/,
    requiredForCompletion: true,
    note: 'Required before publishing endpoint P50/P95/P99 with real LLM/Embedding/Rerank.',
    validate: (content) => [
      [/Real SSE k6 Run Metadata/, 'must include run metadata with concurrency/model/config context'],
      [/vus\s*\|/, 'must include VUS metadata'],
      [/duration\s*\|/, 'must include duration metadata'],
      [/THRESHOLDS/, 'must include k6 thresholds'],
      [/rag_stream_latency/, 'must include custom stream latency metric'],
      [/http_req_duration[\s\S]*p\(95\)/, 'must include HTTP p95 latency'],
      [/http_req_failed[\s\S]*0\.00%/, 'must have zero HTTP failures in saved run'],
    ].filter(([pattern]) => !pattern.test(content)).map(([, message]) => message),
  },
];

if (!fs.existsSync(rawDir)) {
  console.error(`raw-results directory not found: ${rawDir}`);
  process.exit(1);
}

const files = fs.readdirSync(rawDir).sort();
let missing = 0;
let invalid = 0;
for (const check of checks) {
  const matches = files.filter((file) => check.pattern.test(file));
  const latest = matches[matches.length - 1];
  const validationErrors = latest && check.validate
    ? check.validate(fs.readFileSync(path.join(rawDir, latest), 'utf8'))
    : [];
  const ok = matches.length > 0 && validationErrors.length === 0;
  if (matches.length === 0 && check.requiredForCompletion) missing += 1;
  if (matches.length > 0 && validationErrors.length > 0 && check.requiredForCompletion) invalid += 1;
  const marker = matches.length === 0 ? 'MISS' : (ok ? 'OK  ' : 'BAD ');
  console.log(`${marker} ${check.name}`);
  console.log(`     ${check.note}`);
  if (matches.length) {
    console.log(`     latest: benchmarks/raw-results/${latest}`);
    for (const error of validationErrors) {
      console.log(`     invalid: ${error}`);
    }
  }
}

console.log('');
if (missing === 0 && invalid === 0) {
  console.log('All benchmark artifact classes are present and passed content checks. Still verify each file matches the claimed model/vendor/hardware before publishing numbers.');
} else {
  console.log(`${missing} required benchmark artifact class(es) missing.`);
  console.log(`${invalid} required benchmark artifact class(es) failed content checks.`);
  console.log('Run the missing real API / real k6 scripts before marking the overall RAG refactor goal complete.');
}

if (strict && (missing > 0 || invalid > 0)) {
  process.exit(1);
}
