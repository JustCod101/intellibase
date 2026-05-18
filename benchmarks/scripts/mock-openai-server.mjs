#!/usr/bin/env node
import http from 'node:http';

const port = Number(process.env.MOCK_OPENAI_PORT || 18080);
const host = process.env.MOCK_OPENAI_HOST || '127.0.0.1';
const dimensions = Number(process.env.MOCK_EMBEDDING_DIMENSIONS || 1536);
const streamDelayMs = Number(process.env.MOCK_STREAM_DELAY_MS || 0);

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      if (!body.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(body));
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, payload) {
  const text = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
  });
  res.end(text);
}

function fnv1a(text) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

function topicSeed(text) {
  const normalized = String(text || '').toLowerCase();
  if (/pgvector|hnsw|ivfflat|tsvector|gin|rrf|postgres/.test(normalized)) return 1;
  if (/completablefuture|thread|backpressure|jmh|k6|p95|p99/.test(normalized)) return 2;
  if (/rag|hyde|rerank|faithfulness|golden|rewrite/.test(normalized)) return 3;
  if (/tika|minio|ocr|pdf|streaming parser|rabbitmq retry/.test(normalized)) return 4;
  if (/redis|semantic cache|retrieval cache|cache invalidation/.test(normalized)) return 5;
  if (/spring|jwt|securitycontext|rabbitmq|setnx/.test(normalized)) return 0;
  return fnv1a(normalized) % 6;
}

function embeddingFor(text) {
  const seed = topicSeed(text);
  const vector = new Array(dimensions);
  for (let i = 0; i < dimensions; i += 1) {
    vector[i] = Number(((Math.sin((seed + i + 1) * 0.017) + Math.cos((seed - i - 1) * 0.013) + 2.0) / 4.0).toFixed(6));
  }
  return vector;
}

function tokenize(text) {
  return new Set(String(text || '').toLowerCase().match(/[\p{L}\p{N}_]+/gu) || []);
}

function scoreDocument(query, document) {
  const queryTokens = tokenize(query);
  const docTokens = tokenize(typeof document === 'string' ? document : JSON.stringify(document));
  if (queryTokens.size === 0 || docTokens.size === 0) return 0;
  let overlap = 0;
  for (const token of queryTokens) {
    if (docTokens.has(token)) overlap += 1;
  }
  return overlap / Math.sqrt(queryTokens.size * docTokens.size);
}

function extractPrompt(messages) {
  if (!Array.isArray(messages)) return '';
  const lastUser = [...messages].reverse().find(message => message?.role === 'user');
  return lastUser?.content || messages.map(message => message?.content || '').join('\n');
}

function chatAnswer(prompt) {
  return `这是 benchmark mock LLM 的流式回答。问题摘要：${String(prompt).slice(0, 80)}。回答基于已召回上下文，用于端到端压测链路验证，不代表真实模型质量。`;
}

async function sleep(ms) {
  if (ms <= 0) return;
  await new Promise(resolve => setTimeout(resolve, ms));
}

async function handleEmbeddings(req, res) {
  const body = await readJson(req);
  const inputs = Array.isArray(body.input) ? body.input : [body.input ?? ''];
  sendJson(res, 200, {
    object: 'list',
    model: body.model || 'mock-embedding',
    data: inputs.map((input, index) => ({
      object: 'embedding',
      index,
      embedding: embeddingFor(input),
    })),
    usage: { prompt_tokens: inputs.join(' ').length, total_tokens: inputs.join(' ').length },
  });
}

async function handleRerank(req, res) {
  const body = await readJson(req);
  const documents = Array.isArray(body.documents) ? body.documents : [];
  const topN = Number(body.top_n || body.topN || documents.length || 0);
  const results = documents
    .map((document, index) => ({ index, relevance_score: scoreDocument(body.query, document) }))
    .sort((a, b) => b.relevance_score - a.relevance_score)
    .slice(0, topN || documents.length);
  sendJson(res, 200, { model: body.model || 'mock-rerank', results });
}

async function handleChatCompletions(req, res) {
  const body = await readJson(req);
  const prompt = extractPrompt(body.messages);
  const answer = chatAnswer(prompt);

  if (!body.stream) {
    sendJson(res, 200, {
      id: `chatcmpl-mock-${Date.now()}`,
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: body.model || 'mock-chat',
      choices: [{ index: 0, message: { role: 'assistant', content: answer }, finish_reason: 'stop' }],
      usage: { prompt_tokens: prompt.length, completion_tokens: answer.length, total_tokens: prompt.length + answer.length },
    });
    return;
  }

  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache, no-transform',
    connection: 'keep-alive',
  });

  const id = `chatcmpl-mock-${Date.now()}`;
  const writeChunk = payload => res.write(`data: ${JSON.stringify(payload)}\n\n`);
  writeChunk({ id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant' }, finish_reason: null }] });
  for (const piece of answer.match(/.{1,18}/gu) || []) {
    await sleep(streamDelayMs);
    writeChunk({ id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { content: piece }, finish_reason: null }] });
  }
  writeChunk({ id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
  res.write('data: [DONE]\n\n');
  res.end();
}

const server = http.createServer(async (req, res) => {
  try {
    const path = new URL(req.url, `http://${req.headers.host}`).pathname;
    if (req.method === 'GET' && path === '/healthz') {
      sendJson(res, 200, { status: 'ok', dimensions });
      return;
    }
    if (req.method === 'POST' && (path === '/v1/embeddings' || path === '/embeddings')) {
      await handleEmbeddings(req, res);
      return;
    }
    if (req.method === 'POST' && (path === '/v1/chat/completions' || path === '/chat/completions')) {
      await handleChatCompletions(req, res);
      return;
    }
    if (req.method === 'POST' && (path === '/v1/rerank' || path === '/rerank')) {
      await handleRerank(req, res);
      return;
    }
    sendJson(res, 404, { error: `No mock endpoint for ${req.method} ${path}` });
  } catch (error) {
    sendJson(res, 500, { error: error.message });
  }
});

server.listen(port, host, () => {
  console.log(`Mock OpenAI-compatible server listening at http://${host}:${port}`);
  console.log(`Endpoints: /v1/embeddings, /v1/chat/completions, /v1/rerank`);
});
