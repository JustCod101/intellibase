# IntelliBase — 企业级 RAG 知识库平台

基于 SpringBoot 3.2 + LangChain4j 构建的高性能检索增强生成（RAG）系统，面向企业知识管理场景，提供文档智能解析、语义检索、大模型流式问答一体化服务。

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      客户端 (Vue3/React)                      │
│                    SSE 流式接收 · JWT 认证                     │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP / SSE
┌────────────────────────▼────────────────────────────────────┐
│                  SpringBoot 3.2 + JDK 17                     │
│                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │ Auth     │ │ Document │ │ Chat/RAG │ │ KnowledgeBase│   │
│  │ Module   │ │ Module   │ │ Module   │ │ Module       │   │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └──────┬───────┘   │
│       │            │            │               │            │
│  ┌────▼────────────▼────────────▼───────────────▼────────┐  │
│  │              两层缓存体系                               │  │
│  │  L1: 语义缓存 (pgvector >0.95 + 词面锚点校验)           │  │
│  │  L2: 检索缓存 (Redis, query hash → 检索结果, 30min)    │  │
│  └───────────────────────────────────────────────────────┘  │
└──┬──────────┬──────────┬──────────┬─────────────────────────┘
   │          │          │          │
   ▼          ▼          ▼          ▼
┌──────┐ ┌───────┐ ┌─────────┐ ┌───────┐
│Postgre│ │ Redis │ │RabbitMQ │ │ MinIO │
│pgvector│ │  7    │ │  3.13   │ │       │
└──────┘ └───────┘ └─────────┘ └───────┘
```

### 文档处理 Pipeline

```
上传文件 → MinIO 存储 → RabbitMQ(doc.parse) → Tika 解析 → 文本分块(512/64)
    → RabbitMQ(doc.embed) → Embedding API → pgvector 写入
```

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 后端框架 | SpringBoot | 3.2.5 |
| JDK | Eclipse Temurin | 17 |
| ORM | MyBatis-Plus | 3.5.7 |
| 向量数据库 | PostgreSQL + pgvector | 16 |
| 缓存 | Redis | 7 |
| 消息队列 | RabbitMQ | 3.13 |
| 对象存储 | MinIO | latest |
| RAG 框架 | LangChain4j | 0.35 |
| 文档解析 | Apache Tika | 2.9.2 |
| 认证 | Spring Security + JJWT | 0.12.6 |
| API 文档 | SpringDoc OpenAPI | 2.6.0 |

## 快速开始

### 前置要求

- Docker & Docker Compose
- OpenAI 兼容的 API Key（用于 Embedding + LLM）

### 1. 克隆项目

```bash
git clone https://github.com/your-org/intellibase.git
cd intellibase
```

### 2. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 文件：

```dotenv
# 数据库
DB_PASSWORD=your_db_password

# Redis
REDIS_PASSWORD=your_redis_password

# RabbitMQ
MQ_PASSWORD=your_mq_password

# MinIO
MINIO_PASSWORD=your_minio_password

# LLM / Embedding API
OPENAI_API_KEY=sk-xxxx
OPENAI_BASE_URL=https://api.openai.com/v1
LLM_MODEL_NAME=gpt-4o-mini
EMBEDDING_MODEL_NAME=text-embedding-v4
# 必须与 PostgreSQL schema 中的 vector(1536) 一致；更换维度需同时做 schema migration。
EMBEDDING_DIMENSIONS=1536

# Query rewrite / HyDE（默认关闭，真实评测或压测时按需开启）
RAG_QUERY_REWRITE_ENABLED=false
RAG_HYDE_ENABLED=false

# External rerank（默认关闭；配置真实 rerank API 后才能验证精排收益）
RAG_RERANK_EXTERNAL_ENABLED=false
RAG_RERANK_API_URL=
RAG_RERANK_API_KEY=
RAG_RERANK_MODEL=bge-reranker-v2-m3

# LLM-as-judge（仅 evaluation/benchmark runner 使用）
EVALUATION_LLM_JUDGE_API_KEY=
EVALUATION_LLM_JUDGE_BASE_URL=https://api.openai.com/v1
EVALUATION_LLM_JUDGE_MODEL=gpt-4o-mini
```

### 3. 一键启动

```bash
docker compose up -d
```

服务启动后：

| 服务 | 地址 |
|------|------|
| API 服务 | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| 健康检查 | http://localhost:8080/actuator/health |
| RabbitMQ 管理台 | http://localhost:15672 |
| MinIO 控制台 | http://localhost:9001 |

### 4. 本地开发（不用 Docker 运行应用）

先启动基础设施：

```bash
docker compose up -d postgres redis rabbitmq minio
```

然后用 Maven 运行：

```bash
cd intellibase-server
mvn spring-boot:run
```

## API 使用示例

> 完整接口文档见 [docs/api.md](docs/api.md) 或访问 Swagger UI

### 注册用户

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123","email":"admin@example.com"}'
```

### 登录获取 Token

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123"}'
```

返回：

```json
{
  "code": 200,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

### 上传文档到知识库

```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9..."

curl -X POST http://localhost:8080/api/v1/kb/1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@员工手册.pdf"
```

### 流式问答 (SSE)

```bash
curl -N "http://localhost:8080/api/v1/chat/stream?conversationId=1&question=公司年假政策是什么" \
  -H "Authorization: Bearer $TOKEN"
```

SSE 输出：

```
event: token
data: 根据公司年假政策

event: token
data: ，员工入职满一年可享受5天带薪年假...

event: sources
data: [{"chunkId":301,"score":0.92,"snippet":"第三章 休假制度..."}]
```

### 查看缓存统计（管理员）

```bash
curl http://localhost:8080/api/v1/admin/cache/stats \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "code": 200,
  "data": {
    "l1_semantic_cache": { "hits": 15, "misses": 85, "total": 100, "hit_rate": "15.00%" },
    "l2_retrieval_cache": { "hits": 30, "misses": 55, "total": 85, "hit_rate": "35.29%" },
    "db_queries": 55,
    "overall_cache_hit_rate": "35.00%"
  }
}
```

## 项目结构

```
intellibase/
├── docker-compose.yml              # Docker Compose 编排
├── sql/schema.sql                  # 数据库建表脚本
├── docs/
│   ├── intellibase-完整解决方案.md   # 完整设计文档
│   ├── architecture.md             # 架构文档
│   └── api.md                      # API 接口文档
└── intellibase-server/
    ├── Dockerfile                  # 多阶段构建
    ├── pom.xml
    └── src/main/java/com/intellibase/server/
        ├── config/                 # 配置类 (Security, Redis, RabbitMQ, MinIO)
        ├── common/                 # 通用类 (Result, JwtUtils, Constants)
        ├── controller/             # REST 控制器
        ├── domain/                 # 实体、DTO、VO
        │   ├── entity/
        │   ├── dto/
        │   └── vo/
        ├── mapper/                 # MyBatis-Plus Mapper
        ├── service/
        │   ├── auth/               # 认证服务
        │   ├── chat/               # 对话管理
        │   ├── kb/                 # 知识库 & 文档服务
        │   ├── doc/                # 文档解析 & 分块
        │   └── rag/                # RAG 核心 (检索、缓存、Embedding、Prompt)
        ├── consumer/               # MQ 消费者 (DocParse, Embed)
        └── interceptor/            # JWT 过滤器、租户拦截器
```

## 核心特性

- **多格式文档解析** — PDF、Word、PPT、Markdown、TXT，基于 Apache Tika
- **智能文本分块** — RecursiveCharacterTextSplitter，支持多级分隔符 + 重叠窗口
- **异步处理流水线** — RabbitMQ 两阶段异步（解析 → 向量化），上传即返回
- **pgvector 向量检索** — HNSW 向量索引 + 余弦相似度，知识库级别隔离
- **两层缓存** — L1 语义缓存（向量阈值 + sanity check）/ L2 检索结果缓存；已删除 L0 本地缓存与 L3 文档块缓存以降低一致性复杂度
- **SSE 流式输出** — LLM 逐 Token 推送，含引用来源
- **SHA-256 秒传去重** — 相同内容文档自动跳过
- **RBAC 权限** — Spring Security + JWT，ADMIN / USER / VIEWER 三级角色

## License

MIT

## RAG 评测基线（重构评测先行）

当前仓库已加入 60 条 golden QA 离线评测集，覆盖 Spring/RabbitMQ、PostgreSQL/pgvector/RAG、Java/JVM 并发 3 个知识域。

一键运行：

```bash
cd intellibase-server
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) mvn -Dtest=RetrievalEvaluationTest test
```

当前 baseline-fixture（固定样例，用于验证评测管线）结果：Recall@5 = 75.00%，MRR = 45.83%，Hit Rate@5 = 75.00%。另提供：

- `DbBackedRetrievalEvaluationIT`：真实 PostgreSQL/pgvector + seeded deterministic corpus，已验证 Recall@5/MRR/Hit Rate = 100.00%（仅证明 DB-backed runner 可用）。
- `VersionedRetrievalEvaluationIT`：同库切换 dense-only / hybrid RRF / local rerank / query rewrite 配置，seeded matrix 结果为 0.00% → 98.33% → 98.33% → 100.00% Recall@5，原始文件见 `benchmarks/raw-results/versioned-evaluation-report-20260518-232618.md`。
- `RealApiRetrievalEvaluationIT`：真实 embedding API + PostgreSQL/pgvector 的版本对比 runner，可选接入真实 query rewrite / external rerank API；默认关闭，可用 `benchmarks/scripts/run-real-api-evaluation.sh` 一行运行，详见 `docs/evaluation.md`。

以上 seeded 结果不作为真实线上质量 claim；真实文档 + 真实 embedding + 外部 rerank API 的版本对比会追加到 [docs/evaluation.md](docs/evaluation.md)。

## 现代 RAG 重构进展

- Hybrid Search：pgvector 语义召回 + PostgreSQL `tsvector`/GIN 全文召回 + RRF 融合；全文检索使用应用层 tokenizer 生成 OR 型 `tsquery` 扩大粗召回，再由 RRF/rerank 控制噪声。
- Rerank：支持外部 rerank API（默认关闭，配置 `RAG_RERANK_EXTERNAL_ENABLED=true` 后启用），失败自动回退本地排序。
- Query Rewrite：支持 OpenAI-compatible `/chat/completions` 查询改写与可选 HyDE（默认关闭）。
- Parent-Child Chunking：子块用于检索，命中后使用父块上下文进入 Prompt，可在知识库 chunk strategy 中配置。
- Cache：保留 L1 语义缓存与 L2 Redis 检索结果缓存；L1 命中需同时满足向量相似度阈值和轻量 token overlap sanity check；删除 L0 本地缓存和 L3 文档块缓存。

## 性能基准与可复现口径

所有性能数字必须能追溯到 [benchmarks/raw-results](benchmarks/raw-results)。当前已完成 **pgvector 10 万向量单查询索引基准和 200 次采样分位数基准**；同时补充了从仓库真实代码/文档/SQL 切分生成 10 万 real-text chunks 的导入脚本与原始输出。端到端 SSE 的 mock 链路已跑通，真实 LLM/Embedding/Rerank 压测仍需接真实 API 后运行。

| 场景 | 数据规模/条件 | 结果 | 原始文件 |
|---|---|---:|---|
| 生成 fixture | 100,000 chunks，1536 维向量 | 20.870s 导入+索引+ANALYZE | `generate-100k-20260518-233000.txt` |
| 生成 real-text fixture | 100,000 chunks，来自 196 个源码/文档文件，708 个去重 chunk 文本；deterministic fixture vector | 57.040s 导入耗时 | `realtext-generate-100k-20260518-231500.txt` |
| HNSW `ef_search=40` 向量检索 | Top-20，单次 `EXPLAIN ANALYZE` | 0.426ms Execution Time | `pgvector-20260518-233030.txt` |
| HNSW `ef_search=100` | Top-20，单次 `EXPLAIN ANALYZE` | 0.345ms Execution Time | `pgvector-20260518-233030.txt` |
| IVFFlat `lists=100, probes=20` | Top-20，单次 `EXPLAIN ANALYZE` | 150.876ms Execution Time | `pgvector-20260518-233030.txt` |
| GIN 全文召回 | `tsvector @@ tsquery`，Top-20 | 25.865ms Execution Time | `pgvector-20260518-233030.txt` |
| HNSW 多查询分位数 | 200 samples，Top-20，`ef_search=40` | P50 0.189ms / P95 0.290ms / P99 1.039ms | `pgvector-latency-20260518-233100.txt` |
| GIN 多查询分位数 | 200 samples，固定关键词，Top-20 | P50 18.204ms / P95 24.994ms / P99 29.200ms | `pgvector-latency-20260518-233100.txt` |
| SSE mock 端到端 | 1 VU / 5s / 500 chunks / mock OpenAI-compatible API | `http_req_failed=0%`，P95≈8ms | `k6-chat-stream-mock-1vu-500chunks-20260518-231000.txt` |

> pgvector 表格不是端到端问答延迟；不包含 HTTP、真实 Embedding、真实 Rerank、真实 LLM 流式输出。SSE mock 结果只证明链路和脚本可运行，不代表真实模型延迟。`/api/v1/chat/stream` 的真实 P50/P95/P99 需按 `benchmarks/scripts/run-real-chat-stream-k6.sh` 接真实 API 后再填写。
