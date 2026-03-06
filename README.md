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
│  │              三级缓存体系                               │  │
│  │  L1: 语义缓存 (pgvector, >0.95 相似度直接返回)          │  │
│  │  L2: 检索缓存 (Redis, query hash → 检索结果, 30min)    │  │
│  │  L3: 文档缓存 (Redis, chunk 内容, 2h, LRU)            │  │
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
LLM_MODEL_NAME=gpt-4o
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
    "l3_chunk_cache": { "hits": 200, "misses": 75, "total": 275, "hit_rate": "72.73%" },
    "db_queries": 55,
    "overall_cache_hit_rate": "45.00%"
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
- **pgvector 向量检索** — IVFFlat 索引 + 余弦相似度，知识库级别隔离
- **三级缓存** — L1 语义缓存 / L2 检索缓存 / L3 文档缓存，命中率可观测
- **SSE 流式输出** — LLM 逐 Token 推送，含引用来源
- **SHA-256 秒传去重** — 相同内容文档自动跳过
- **RBAC 权限** — Spring Security + JWT，ADMIN / USER / VIEWER 三级角色

## License

MIT
