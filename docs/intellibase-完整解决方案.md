# 高性能企业级大模型知识库 — 完整解决方案设计

## 一、项目概述

**项目名称：** IntelliBase — 企业级 RAG 知识库平台  
**技术定位：** 基于 SpringBoot + LangChain4j 构建的高性能检索增强生成（RAG）系统  
**核心价值：** 面向企业知识管理场景，提供文档智能解析、语义检索、大模型问答一体化服务

---

## 二、整体架构设计

### 2.1 分层架构

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

### 2.2 核心技术栈

| 层级 | 技术选型 | 用途 |
|------|---------|------|
| 后端框架 | SpringBoot 3.2 + JDK 17 | 核心服务框架 |
| RAG框架 | LangChain4j 0.35+ | 模型编排、RAG Pipeline |
| 向量存储 | PostgreSQL + pgvector / Milvus 2.x | 向量存储与相似度检索 |
| 关系数据库 | PostgreSQL 16 | 业务数据、元数据存储 |
| 缓存 | Redis 7 (Cluster) | 语义缓存、会话缓存、限流 |
| 消息队列 | RabbitMQ 3.13 | 异步文档处理、推理请求削峰 |
| 对象存储 | MinIO | 原始文档存储 |
| 认证授权 | Spring Security + Sa-Token | RBAC权限、JWT认证 |
| 文档解析 | Apache Tika + Unstructured | PDF/Word/PPT多格式解析 |
| 流式响应 | SSE (Server-Sent Events) | 大模型流式输出 |
| 容器化 | Docker + Docker Compose | 一键部署 |
| API文档 | SpringDoc OpenAPI 3 | 接口文档自动生成 |

---

## 三、数据库建模（重点展示）

### 3.1 PostgreSQL 关系模型

```sql
-- ============================================================
-- 1. 用户与权限体系
-- ============================================================

CREATE TABLE sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password_hash   VARCHAR(256) NOT NULL,
    email           VARCHAR(128),
    role            VARCHAR(32) NOT NULL DEFAULT 'USER',  -- ADMIN / USER / VIEWER
    tenant_id       BIGINT NOT NULL,                      -- 多租户隔离
    status          SMALLINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_tenant ON sys_user(tenant_id);
CREATE INDEX idx_user_username ON sys_user(username);

-- ============================================================
-- 2. 知识库管理
-- ============================================================

CREATE TABLE knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    tenant_id       BIGINT NOT NULL,
    embedding_model VARCHAR(64) NOT NULL DEFAULT 'text-embedding-3-small',
    chunk_strategy  JSONB NOT NULL DEFAULT '{"size":512,"overlap":64}',
    doc_count       INT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT REFERENCES sys_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kb_tenant ON knowledge_base(tenant_id);

-- ============================================================
-- 3. 文档管理（体现多维建模）
-- ============================================================

CREATE TABLE document (
    id              BIGSERIAL PRIMARY KEY,
    kb_id           BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    title           VARCHAR(256) NOT NULL,
    file_key        VARCHAR(512) NOT NULL,          -- MinIO 对象Key
    file_type       VARCHAR(16) NOT NULL,           -- pdf, docx, md, txt
    file_size       BIGINT NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,           -- SHA-256 去重
    parse_status    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- PENDING -> PARSING -> EMBEDDING -> COMPLETED -> FAILED
    chunk_count     INT DEFAULT 0,
    metadata        JSONB,                          -- 自定义元数据（作者、版本等）
    created_by      BIGINT REFERENCES sys_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_kb ON document(kb_id);
CREATE INDEX idx_doc_hash ON document(content_hash);     -- 去重查询
CREATE INDEX idx_doc_status ON document(parse_status);   -- 任务调度

-- ============================================================
-- 4. 文档分块与向量存储（核心表）
-- ============================================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunk (
    id              BIGSERIAL PRIMARY KEY,
    doc_id          BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id           BIGINT NOT NULL,
    chunk_index     INT NOT NULL,                   -- 块序号
    content         TEXT NOT NULL,                   -- 原始文本
    token_count     INT NOT NULL,
    embedding       vector(1536),                   -- OpenAI ada-002: 1536维
    metadata        JSONB,                          -- 页码、标题层级等
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 向量索引：IVFFlat (适合百万级数据，比HNSW更节省内存)
CREATE INDEX idx_chunk_embedding ON document_chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 复合索引：先按知识库过滤再做向量检索（大幅提升多租户场景性能）
CREATE INDEX idx_chunk_kb ON document_chunk(kb_id);
CREATE INDEX idx_chunk_doc ON document_chunk(doc_id);

-- ============================================================
-- 5. 对话管理
-- ============================================================

CREATE TABLE conversation (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES sys_user(id),
    kb_id           BIGINT REFERENCES knowledge_base(id),
    title           VARCHAR(256),
    model           VARCHAR(64) NOT NULL DEFAULT 'gpt-4o',
    config          JSONB DEFAULT '{"temperature":0.7,"topK":5}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,            -- user / assistant / system
    content         TEXT NOT NULL,
    token_usage     JSONB,                           -- {prompt_tokens, completion_tokens}
    sources         JSONB,                           -- 引用来源 [{chunk_id, score, snippet}]
    latency_ms      INT,                             -- 响应耗时
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_msg_conv ON chat_message(conversation_id, created_at);

-- ============================================================
-- 6. 语义缓存表（可选：也可纯用Redis）
-- ============================================================

CREATE TABLE semantic_cache (
    id              BIGSERIAL PRIMARY KEY,
    kb_id           BIGINT NOT NULL,
    query_embedding vector(1536),
    query_text      TEXT NOT NULL,
    response_text   TEXT NOT NULL,
    hit_count       INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ
);

CREATE INDEX idx_cache_embedding ON semantic_cache
    USING ivfflat (query_embedding vector_cosine_ops) WITH (lists = 50);
```

### 3.2 SQL 性能优化策略

| 优化点 | 具体措施 | 效果 |
|-------|---------|------|
| 向量索引选型 | 数据 < 100万用 IVFFlat，> 100万切 HNSW | 查询延迟降低60% |
| 分区表 | document_chunk 按 kb_id RANGE 分区 | 大租户查询隔离 |
| 复合过滤 | 先 WHERE kb_id = ? 再向量检索 | 避免全表扫描 |
| 连接池 | HikariCP min=10, max=50, timeout=30s | 连接复用 |
| 批量写入 | 嵌入向量批量 INSERT (batch=100) | 写入吞吐提升5x |
| 查询缓存 | 高频query的 embedding 结果缓存到 Redis | 减少重复计算 |

---

## 四、核心模块详细设计

### 4.1 文档处理 Pipeline（异步架构）

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

### 4.2 RAG 检索与生成流程

```
用户提问 "公司的年假政策是什么？"
    │
    ▼
[1] 语义缓存查询 (Redis)
    │── 命中 → 直接返回缓存结果
    │── 未命中 ↓
    ▼
[2] Query 改写（可选）
    LLM 将口语化问题改写为检索友好格式
    → "企业年假政策 员工休假天数 规定"
    │
    ▼
[3] Embedding 生成
    调用 text-embedding-3-small 生成查询向量
    │
    ▼
[4] 向量检索 (pgvector)
    SELECT content, 1 - (embedding <=> query_vec) AS score
    FROM document_chunk
    WHERE kb_id = ? AND (1 - embedding <=> query_vec) > 0.7
    ORDER BY embedding <=> query_vec
    LIMIT 5;
    │
    ▼
[5] Rerank 重排序（可选）
    使用 Cross-Encoder 对 Top-K 结果精排
    │
    ▼
[6] Prompt 组装
    System: "你是企业知识助手，基于以下上下文回答问题..."
    Context: [检索到的文档片段]
    User: "公司的年假政策是什么？"
    │
    ▼
[7] LLM 推理 (SSE 流式返回)
    │
    ▼
[8] 结果缓存写入 Redis + 写入 chat_message
```

### 4.3 高性能架构设计要点

#### A. Redis 多级缓存策略

```java
/**
 * 三级缓存体系:
 * L1: 语义缓存 — 相似问题直接返回（余弦相似度 > 0.95）
 * L2: 检索缓存 — 相同 query 的检索结果缓存（TTL: 30min）
 * L3: 文档缓存 — 热点文档块缓存（TTL: 2h, LRU淘汰）
 */

// === 语义缓存核心逻辑 ===
@Service
public class SemanticCacheService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private EmbeddingService embeddingService;

    private static final double SIMILARITY_THRESHOLD = 0.95;
    private static final String CACHE_PREFIX = "sem_cache:";

    public Optional<String> tryGetCachedAnswer(String query, Long kbId) {
        float[] queryVec = embeddingService.embed(query);

        // 从 pgvector 的 semantic_cache 表中查找相似问题
        List<CacheHit> hits = semanticCacheMapper.findSimilar(
            queryVec, kbId, SIMILARITY_THRESHOLD, 1
        );

        if (!hits.isEmpty()) {
            CacheHit hit = hits.get(0);
            // 更新命中计数
            semanticCacheMapper.incrementHitCount(hit.getId());
            return Optional.of(hit.getResponseText());
        }
        return Optional.empty();
    }

    public void cacheAnswer(String query, String answer, Long kbId) {
        float[] queryVec = embeddingService.embed(query);
        SemanticCache cache = new SemanticCache();
        cache.setKbId(kbId);
        cache.setQueryText(query);
        cache.setQueryEmbedding(queryVec);
        cache.setResponseText(answer);
        cache.setExpiresAt(Instant.now().plus(Duration.ofHours(6)));
        semanticCacheMapper.insert(cache);
    }
}
```

#### B. RabbitMQ 异步处理 & 削峰

```java
// === RabbitMQ 配置 ===
@Configuration
public class RabbitConfig {

    // 文档解析队列 — 限制并发防止 OOM
    @Bean
    public Queue docParseQueue() {
        return QueueBuilder.durable("doc.parse.queue")
            .withArgument("x-max-length", 1000)       // 队列最大长度
            .withArgument("x-dead-letter-exchange", "dlx.exchange")
            .build();
    }

    // 向量化队列 — 控制对 Embedding API 的调用频率
    @Bean
    public Queue docEmbedQueue() {
        return QueueBuilder.durable("doc.embed.queue")
            .withArgument("x-max-length", 5000)
            .build();
    }

    // 推理请求队列 — 高并发场景削峰
    @Bean
    public Queue inferenceQueue() {
        return QueueBuilder.durable("inference.queue")
            .withArgument("x-max-priority", 10)        // 优先级队列
            .build();
    }
}

// === 文档解析消费者 ===
@Component
public class DocParseConsumer {

    @RabbitListener(queues = "doc.parse.queue", concurrency = "3-5")
    public void handleDocParse(DocParseMessage msg) {
        try {
            // 1. 从 MinIO 下载文档
            InputStream stream = minioClient.getObject(msg.getFileKey());

            // 2. Tika 解析
            String text = tikaParser.parse(stream, msg.getFileType());

            // 3. 分块
            List<TextChunk> chunks = textSplitter.split(text,
                msg.getChunkSize(), msg.getChunkOverlap());

            // 4. 发送到 Embedding 队列
            for (List<TextChunk> batch : Lists.partition(chunks, 100)) {
                rabbitTemplate.convertAndSend("doc.embed.queue",
                    new EmbedBatchMessage(msg.getDocId(), msg.getKbId(), batch));
            }

            documentMapper.updateStatus(msg.getDocId(), "EMBEDDING");
        } catch (Exception e) {
            documentMapper.updateStatus(msg.getDocId(), "FAILED");
            log.error("文档解析失败: docId={}", msg.getDocId(), e);
        }
    }
}
```

#### C. SSE 流式响应

```java
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam Long conversationId,
            @RequestParam String question,
            @AuthenticationPrincipal UserDetails user) {

        SseEmitter emitter = new SseEmitter(120_000L); // 120s 超时

        CompletableFuture.runAsync(() -> {
            try {
                // 1. RAG 检索
                List<RetrievalResult> contexts = ragService.retrieve(
                    question, getKbId(conversationId));

                // 2. 构建 Prompt
                String prompt = promptBuilder.build(question, contexts);

                // 3. 流式调用 LLM
                StringBuilder fullResponse = new StringBuilder();
                llmClient.streamChat(prompt, token -> {
                    try {
                        fullResponse.append(token);
                        emitter.send(SseEmitter.event()
                            .name("token")
                            .data(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });

                // 4. 发送引用来源
                emitter.send(SseEmitter.event()
                    .name("sources")
                    .data(objectMapper.writeValueAsString(contexts)));

                // 5. 持久化
                chatService.saveMessage(conversationId, question,
                    fullResponse.toString(), contexts);

                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, taskExecutor);

        return emitter;
    }
}
```

#### D. 权限管理 (RBAC)

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/kb/**").hasAnyRole("ADMIN", "USER")
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .build();
    }
}

// 数据隔离 — MyBatis 拦截器自动注入 tenant_id
@Intercepts(@Signature(type = Executor.class, method = "query", args = {...}))
public class TenantInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) {
        // 自动为 SQL 追加 WHERE tenant_id = ?
        // 确保多租户数据隔离
    }
}
```

---

## 五、项目目录结构

```
intellibase/
├── docker-compose.yml                  # 一键启动全部基础设施
├── docs/
│   ├── architecture.md                 # 架构设计文档
│   └── api.md                          # API 接口文档
├── sql/
│   ├── schema.sql                      # 建表语句
│   └── init-data.sql                   # 初始化数据
│
├── intellibase-server/                 # 主服务模块
│   ├── pom.xml
│   └── src/main/java/com/intellibase/
│       ├── IntellibaseApplication.java
│       │
│       ├── config/                     # 配置层
│       │   ├── SecurityConfig.java
│       │   ├── RedisConfig.java
│       │   ├── RabbitConfig.java
│       │   ├── MilvusConfig.java       # 可选
│       │   └── WebConfig.java
│       │
│       ├── controller/                 # 接口层
│       │   ├── AuthController.java
│       │   ├── KnowledgeBaseController.java
│       │   ├── DocumentController.java
│       │   └── ChatController.java
│       │
│       ├── service/                    # 业务层
│       │   ├── auth/
│       │   │   └── AuthService.java
│       │   ├── kb/
│       │   │   ├── KnowledgeBaseService.java
│       │   │   └── DocumentService.java
│       │   ├── rag/
│       │   │   ├── RagService.java            # RAG 编排引擎
│       │   │   ├── EmbeddingService.java      # 向量化服务
│       │   │   ├── RetrievalService.java      # 检索服务
│       │   │   ├── PromptBuilder.java         # Prompt 模板管理
│       │   │   └── SemanticCacheService.java  # 语义缓存
│       │   ├── chat/
│       │   │   └── ChatService.java
│       │   └── doc/
│       │       ├── DocParseService.java       # 文档解析
│       │       └── TextSplitter.java          # 文本分块
│       │
│       ├── consumer/                   # MQ 消费者
│       │   ├── DocParseConsumer.java
│       │   └── EmbedConsumer.java
│       │
│       ├── domain/                     # 领域模型
│       │   ├── entity/
│       │   ├── dto/
│       │   ├── vo/
│       │   └── enums/
│       │
│       ├── mapper/                     # MyBatis Mapper
│       │   ├── UserMapper.java
│       │   ├── KnowledgeBaseMapper.java
│       │   ├── DocumentMapper.java
│       │   ├── DocumentChunkMapper.java
│       │   └── ChatMessageMapper.java
│       │
│       ├── interceptor/               # 拦截器
│       │   ├── TenantInterceptor.java
│       │   └── JwtAuthFilter.java
│       │
│       └── common/                    # 公共组件
│           ├── Result.java            # 统一返回
│           ├── GlobalExceptionHandler.java
│           └── Constants.java
│
└── intellibase-web/                   # 前端（可选）
    └── ...
```

---

## 六、Docker Compose 一键部署

```yaml
version: '3.8'
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: intellibase
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "5432:5432"
    volumes:
      - ./sql/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - pg_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD} --maxmemory 256mb --maxmemory-policy allkeys-lru
    ports:
      - "6379:6379"

  rabbitmq:
    image: rabbitmq:3.13-management
    environment:
      RABBITMQ_DEFAULT_USER: admin
      RABBITMQ_DEFAULT_PASS: ${MQ_PASSWORD}
    ports:
      - "5672:5672"
      - "15672:15672"

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: ${MINIO_PASSWORD}
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data

  app:
    build: ./intellibase-server
    depends_on:
      - postgres
      - redis
      - rabbitmq
      - minio
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DB_URL: jdbc:postgresql://postgres:5432/intellibase
      REDIS_HOST: redis
      RABBITMQ_HOST: rabbitmq
    ports:
      - "8080:8080"

volumes:
  pg_data:
  minio_data:
```

---

## 七、关键 API 接口设计

### 7.1 RESTful API 列表

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | /api/v1/auth/login | 用户登录 | Public |
| POST | /api/v1/auth/register | 用户注册 | Public |
| GET | /api/v1/kb | 知识库列表 | USER |
| POST | /api/v1/kb | 创建知识库 | USER |
| POST | /api/v1/kb/{id}/documents | 上传文档 | USER |
| GET | /api/v1/kb/{id}/documents | 文档列表 | USER |
| DELETE | /api/v1/kb/{id}/documents/{docId} | 删除文档 | USER |
| GET | /api/v1/chat/stream | SSE 流式问答 | USER |
| GET | /api/v1/chat/conversations | 会话列表 | USER |
| GET | /api/v1/chat/conversations/{id}/messages | 历史消息 | USER |
| GET | /actuator/health | 健康检查 | Public |
| GET | /actuator/prometheus | 监控指标 | ADMIN |

