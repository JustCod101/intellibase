import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    chat_stream_smoke: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || '1m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    rag_stream_status_ok: ['rate>0.95'],
    rag_stream_latency: ['p(95)<120000'],
  },
};

const streamLatency = new Trend('rag_stream_latency');
const statusOk = new Rate('rag_stream_status_ok');

const questions = [
  'pgvector HNSW 和 IVFFlat 的核心区别是什么？',
  'RabbitMQ 消费者如何做到幂等处理？',
  'RRF 融合排序为什么适合混合检索？',
  'Java 线程池为什么不建议无界队列？',
  '父子分块为什么能提升 RAG 质量？',
];

export default function () {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  const token = __ENV.AUTH_TOKEN;
  const conversationId = __ENV.CONVERSATION_ID;
  if (!token || !conversationId) {
    throw new Error('AUTH_TOKEN and CONVERSATION_ID are required');
  }

  const question = questions[Math.floor(Math.random() * questions.length)];
  const url = `${baseUrl}/api/v1/chat/stream?conversationId=${conversationId}&question=${encodeURIComponent(question)}`;
  const started = Date.now();
  const res = http.get(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'text/event-stream',
    },
    timeout: __ENV.TIMEOUT || '120s',
  });
  const elapsed = Date.now() - started;
  streamLatency.add(elapsed);
  statusOk.add(res.status === 200);
  check(res, {
    'status is 200': r => r.status === 200,
    'contains token or sources event': r => r.body.includes('event:token') || r.body.includes('event:sources'),
  });
  sleep(Number(__ENV.SLEEP_SECONDS || 1));
}
