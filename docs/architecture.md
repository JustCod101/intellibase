# IntelliBase 架构设计文档

## 1. 项目简介

**IntelliBase** 是一个企业级 RAG（检索增强生成）知识库平台，基于 SpringBoot 3.2 + LangChain4j 构建，面向企业知识管理场景，提供文档智能解析、语义检索、大模型问答一体化服务。

## 2. 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                    Presentation Layer                         │
│  Vue3/React 前端  ←──SSE/WebSocket──→  Nginx 反向代理         │
├──────────────────────────────────────────────────────────────┤
│                    Gateway Layer                              │
│  Spring Cloud Gateway · JWT 认证 · 限流(Sentinel) · 路由      │
├──────────────────────────────────────────────────────────────┤
│                    Application Layer (SpringBoot)             │
│  ┌─────────┐ ┌──────────┐ ┌───────────┐ ┌───────────────┐   │
│  │文档服务  │ │检索服务   │ │对话服务    │ │知识库管理服务  │   │
│  │Doc Svc  │ │Search Svc│ │Chat Svc   │ │KB Mgmt Svc    │   │
│  └────┬────┘ └────┬─────┘ └─────┬─────┘ └──────┬────────┘   │
├───────┼───────────┼─────────────┼───────────────┼────────────┤
│                    Domain Layer                                │
│  ┌─────────┐ ┌──────────┐ ┌───────────┐ ┌───────────────┐   │
│  │文档解析  │ │向量化引擎 │ │RAG编排引擎│ │权限/租户管理   │   │
│  │Pipeline │ │Embedding │ │RAG Engine │ │Auth Module    │   │
│  └────┬────┘ └────┬─────┘ └─────┬─────┘ └──────┬────────┘   │
├───────┼───────────┼─────────────┼───────────────┼────────────┤
│                    Infrastructure Layer                        │
│  PostgreSQL(pgvector) │ Redis │ RabbitMQ │ MinIO │ Milvus    │
└──────────────────────────────────────────────────────────────┘
```

### 各层职责

| 层级 | 职责 |
|------|------|
| Presentation Layer | 前端 UI 交互，通过 SSE/WebSocket 实现流式响应 |
| Gateway Layer | 统一入口，负责 JWT 认证、限流、路由转发 |
| Application Layer | 核心业务服务：文档管理、检索、对话、知识库 CRUD |
| Domain Layer | 领域逻辑：文档解析 Pipeline、向量化引擎、RAG 编排、权限管理 |
| Infrastructure Layer | 基础设施：数据库、缓存、消息队列、对象存储 |

## 3. 核心技术栈

| 层级 | 技术选型 | 用途 |
|------|---------|------|
| 后端框架 | SpringBoot 3.2 + JDK 17 | 核心服务框架 |
| RAG 框架 | LangChain4j 0.35+ | 模型编排、RAG Pipeline |
| 向量存储 | PostgreSQL + pgvector / Milvus 2.x | 向量存储与相似度检索 |
| 关系数据库 | PostgreSQL 16 | 业务数据、元数据存储 |
| 缓存 | Redis 7 (Cluster) | 语义缓存、会话缓存、限流 |
| 消息队列 | RabbitMQ 3.13 | 异步文档处理、推理请求削峰 |
| 对象存储 | MinIO | 原始文档存储 |
| 认证授权 | Spring Security + Sa-Token | RBAC 权限、JWT 认证 |
| 文档解析 | Apache Tika + Unstructured | PDF/Word/PPT 多格式解析 |
| 流式响应 | SSE (Server-Sent Events) | 大模型流式输出 |
| 容器化 | Docker + Docker Compose | 一键部署 |
| API 文档 | SpringDoc OpenAPI 3 | 接口文档自动生成 |

## 4. 核心模块设计

### 4.1 文档处理 Pipeline（异步架构）

文档从上传到可检索，经历以下异步流水线：

```
用户上传文档
    │
    ▼
[REST API] ──存储原文──→ MinIO
    │
    ▼
[发送消息] ──→ RabbitMQ (doc.parse.queue)
                     │
                     ▼
              [消费者: DocParseConsumer]
                     │
           ┌─────────┼──────────┐
           ▼         ▼          ▼
        PDF解析   Word解析   Markdown解析
        (Tika)    (Tika)     (直接读取)
           │         │          │
           └─────────┼──────────┘
                     ▼
              [文本分块 TextSplitter]
              RecursiveCharacterSplitter
              size=512, overlap=64
                     │
                     ▼
              [发送消息] ──→ RabbitMQ (doc.embed.queue)
                                  │
                                  ▼
                          [消费者: EmbedConsumer]
                          批量调用 Embedding API
                          batch_size=100
                                  │
                                  ▼
                          [批量写入 pgvector]
                          状态更新: COMPLETED
```

**文档状态流转：** `PENDING → PARSING → EMBEDDING → COMPLETED / FAILED`

### 4.2 RAG 检索与生成流程

```
用户提问
    │
    ▼
[1] 语义缓存查询 (Redis/pgvector)
    │── 命中 → 直接返回缓存结果
    │── 未命中 ↓
    ▼
[2] Query 改写（可选）
    LLM 将口语化问题改写为检索友好格式
    │
    ▼
[3] Embedding 生成
    调用 text-embedding-3-small 生成查询向量
    │
    ▼
[4] 向量检索 (pgvector)
    基于余弦相似度检索 Top-K 文档块
    支持按 kb_id 过滤（多租户隔离）
    │
    ▼
[5] Rerank 重排序（可选）
    使用 Cross-Encoder 对 Top-K 结果精排
    │
    ▼
[6] Prompt 组装
    System Prompt + 检索上下文 + 用户问题
    │
    ▼
[7] LLM 推理 (SSE 流式返回)
    │
    ▼
[8] 结果缓存 + 持久化到 chat_message
```

### 4.3 高性能设计要点

#### Redis 三级缓存体系

| 级别 | 缓存类型 | 策略 |
|------|---------|------|
| L1 | 语义缓存 | 相似问题直接返回（余弦相似度 > 0.95） |
| L2 | 检索缓存 | 相同 query 的检索结果缓存（TTL: 30min） |
| L3 | 文档缓存 | 热点文档块缓存（TTL: 2h, LRU 淘汰） |

#### RabbitMQ 队列设计

| 队列 | 用途 | 配置 |
|------|------|------|
| doc.parse.queue | 文档解析 | max-length=1000, 死信队列, 并发 3-5 |
| doc.embed.queue | 向量化 | max-length=5000, 控制 Embedding API 调用频率 |
| inference.queue | 推理请求 | 优先级队列（max-priority=10）, 高并发削峰 |

#### 权限与多租户

- **认证方式：** JWT 无状态认证（Spring Security + Sa-Token）
- **权限模型：** RBAC（ADMIN / USER / VIEWER）
- **数据隔离：** MyBatis 拦截器自动注入 `tenant_id` 条件，确保多租户数据隔离

## 5. 数据库设计

### 核心表结构

| 表名 | 说明 |
|------|------|
| sys_user | 用户表，支持多租户（tenant_id） |
| knowledge_base | 知识库，包含 embedding 模型和分块策略配置 |
| document | 文档元数据，记录解析状态和 MinIO 文件引用 |
| document_chunk | 文档分块 + 向量存储（pgvector, 1536 维） |
| conversation | 对话会话，关联用户和知识库 |
| chat_message | 聊天消息，包含引用来源和 Token 用量 |
| semantic_cache | 语义缓存表，基于向量相似度命中 |

### SQL 性能优化策略

| 优化点 | 具体措施 | 效果 |
|-------|---------|------|
| 向量索引选型 | 数据 < 100 万用 IVFFlat，> 100 万切 HNSW | 查询延迟降低 60% |
| 分区表 | document_chunk 按 kb_id RANGE 分区 | 大租户查询隔离 |
| 复合过滤 | 先 WHERE kb_id = ? 再向量检索 | 避免全表扫描 |
| 连接池 | HikariCP min=10, max=50, timeout=30s | 连接复用 |
| 批量写入 | 嵌入向量批量 INSERT (batch=100) | 写入吞吐提升 5x |
| 查询缓存 | 高频 query 的 embedding 结果缓存到 Redis | 减少重复计算 |

## 6. 项目目录结构

```
intellibase/
├── docker-compose.yml              # 一键启动全部基础设施
├── docs/
│   ├── architecture.md             # 架构设计文档（本文件）
│   └── api.md                      # API 接口文档
├── sql/
│   ├── schema.sql                  # 建表语句
│   └── init-data.sql               # 初始化数据
│
├── intellibase-server/             # 主服务模块
│   ├── pom.xml
│   └── src/main/java/com/intellibase/server/
│       ├── IntellibaseServerApplication.java
│       ├── config/                 # 配置层
│       ├── controller/             # 接口层
│       ├── service/                # 业务层
│       │   └── impl/
│       ├── consumer/               # MQ 消费者
│       ├── domain/                 # 领域模型
│       │   ├── entity/
│       │   ├── dto/
│       │   ├── vo/
│       │   └── enums/
│       ├── mapper/                 # MyBatis Mapper
│       ├── interceptor/            # 拦截器（JWT、多租户）
│       └── common/                 # 公共组件（统一返回、异常处理）
│
└── intellibase-web/                # 前端（可选）
```

## 7. 部署架构

使用 Docker Compose 一键部署所有基础设施：

| 服务 | 镜像 | 端口 |
|------|------|------|
| PostgreSQL + pgvector | pgvector/pgvector:pg16 | 5432 |
| Redis | redis:7-alpine | 6379 |
| RabbitMQ | rabbitmq:3.13-management | 5672 / 15672 |
| MinIO | minio/minio | 9000 / 9001 |
| Application | 自构建 | 8080 |
