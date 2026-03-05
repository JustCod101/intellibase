# IntelliBase API 接口文档

> Base URL: `http://localhost:8080/api/v1`
> 认证方式: Bearer Token (JWT)
> 在线文档: `http://localhost:8080/swagger-ui.html`

## 1. 认证接口

### 1.1 用户登录

```
POST /api/v1/auth/login
```

**权限：** Public

**请求体：**

```json
{
  "username": "admin",
  "password": "password123"
}
```

**响应：**

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

---

### 1.2 用户注册

```
POST /api/v1/auth/register
```

**权限：** Public

**请求体：**

```json
{
  "username": "newuser",
  "password": "password123",
  "email": "user@example.com"
}
```

**响应：**

```json
{
  "code": 200,
  "data": {
    "id": 1,
    "username": "newuser",
    "email": "user@example.com",
    "role": "USER"
  }
}
```

---

## 2. 知识库管理

### 2.1 获取知识库列表

```
GET /api/v1/kb
```

**权限：** USER

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页条数，默认 10 |
| keyword | string | 否 | 搜索关键词 |

**响应：**

```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "name": "公司制度库",
        "description": "包含所有公司规章制度文档",
        "embeddingModel": "text-embedding-3-small",
        "chunkStrategy": { "size": 512, "overlap": 64 },
        "docCount": 25,
        "status": "ACTIVE",
        "createdAt": "2026-03-01T10:00:00Z"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 10
  }
}
```

---

### 2.2 创建知识库

```
POST /api/v1/kb
```

**权限：** USER

**请求体：**

```json
{
  "name": "产品文档库",
  "description": "产品使用手册和技术文档",
  "embeddingModel": "text-embedding-3-small",
  "chunkStrategy": {
    "size": 512,
    "overlap": 64
  }
}
```

**响应：**

```json
{
  "code": 200,
  "data": {
    "id": 2,
    "name": "产品文档库",
    "description": "产品使用手册和技术文档",
    "embeddingModel": "text-embedding-3-small",
    "docCount": 0,
    "status": "ACTIVE",
    "createdAt": "2026-03-05T12:00:00Z"
  }
}
```

---

## 3. 文档管理

### 3.1 上传文档

```
POST /api/v1/kb/{kbId}/documents
```

**权限：** USER

**请求方式：** `multipart/form-data`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | file | 是 | 文档文件（支持 pdf, docx, md, txt） |
| metadata | string | 否 | JSON 格式的自定义元数据 |

**响应：**

```json
{
  "code": 200,
  "data": {
    "id": 101,
    "kbId": 1,
    "title": "员工手册2026.pdf",
    "fileType": "pdf",
    "fileSize": 2048576,
    "parseStatus": "PENDING",
    "createdAt": "2026-03-05T12:30:00Z"
  }
}
```

**文档处理状态流转：** `PENDING → PARSING → EMBEDDING → COMPLETED / FAILED`

---

### 3.2 获取文档列表

```
GET /api/v1/kb/{kbId}/documents
```

**权限：** USER

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页条数，默认 10 |
| status | string | 否 | 按解析状态过滤 |

**响应：**

```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 101,
        "kbId": 1,
        "title": "员工手册2026.pdf",
        "fileType": "pdf",
        "fileSize": 2048576,
        "parseStatus": "COMPLETED",
        "chunkCount": 47,
        "metadata": { "author": "HR部门", "version": "3.0" },
        "createdAt": "2026-03-05T12:30:00Z"
      }
    ],
    "total": 25,
    "page": 1,
    "size": 10
  }
}
```

---

### 3.3 删除文档

```
DELETE /api/v1/kb/{kbId}/documents/{docId}
```

**权限：** USER

**响应：**

```json
{
  "code": 200,
  "message": "文档已删除"
}
```

> 删除文档将同时删除关联的所有文档分块和向量数据（级联删除）。

---

## 4. 对话与问答

### 4.1 SSE 流式问答

```
GET /api/v1/chat/stream
```

**权限：** USER

**Content-Type:** `text/event-stream`

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conversationId | long | 是 | 会话 ID |
| question | string | 是 | 用户问题 |

**SSE 事件流：**

```
event: token
data: 根据

event: token
data: 公司

event: token
data: 年假政策

event: token
data: ，员工入职满一年可享受5天带薪年假...

event: sources
data: [{"chunkId":301,"score":0.92,"snippet":"第三章 休假制度：员工入职满一年..."}]
```

**事件类型：**

| 事件名 | 说明 |
|--------|------|
| token | LLM 逐 token 输出的文本片段 |
| sources | 检索引用来源（包含 chunkId、相似度分数、文本摘要） |

---

### 4.2 获取会话列表

```
GET /api/v1/chat/conversations
```

**权限：** USER

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页条数，默认 20 |

**响应：**

```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "kbId": 1,
        "title": "关于年假政策的咨询",
        "model": "gpt-4o",
        "config": { "temperature": 0.7, "topK": 5 },
        "createdAt": "2026-03-05T14:00:00Z",
        "updatedAt": "2026-03-05T14:05:00Z"
      }
    ],
    "total": 10,
    "page": 1,
    "size": 20
  }
}
```

---

### 4.3 获取会话历史消息

```
GET /api/v1/chat/conversations/{conversationId}/messages
```

**权限：** USER

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页条数，默认 50 |

**响应：**

```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "conversationId": 1,
        "role": "user",
        "content": "公司的年假政策是什么？",
        "createdAt": "2026-03-05T14:00:00Z"
      },
      {
        "id": 2,
        "conversationId": 1,
        "role": "assistant",
        "content": "根据公司年假政策，员工入职满一年可享受5天带薪年假...",
        "tokenUsage": { "promptTokens": 1200, "completionTokens": 350 },
        "sources": [
          { "chunkId": 301, "score": 0.92, "snippet": "第三章 休假制度..." }
        ],
        "latencyMs": 2350,
        "createdAt": "2026-03-05T14:00:03Z"
      }
    ],
    "total": 2,
    "page": 1,
    "size": 50
  }
}
```

---

## 5. 监控接口

### 5.1 健康检查

```
GET /actuator/health
```

**权限：** Public

**响应：**

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "rabbit": { "status": "UP" }
  }
}
```

---

### 5.2 监控指标

```
GET /actuator/prometheus
```

**权限：** ADMIN

---

## 6. 统一响应格式

所有接口遵循统一响应结构：

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

**错误响应：**

```json
{
  "code": 401,
  "message": "未授权，请先登录",
  "data": null
}
```

**常见状态码：**

| code | 说明 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

## 7. 认证说明

除标注为 Public 的接口外，所有接口需在请求头中携带 JWT Token：

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

**权限角色：**

| 角色 | 说明 |
|------|------|
| ADMIN | 管理员，拥有所有权限 |
| USER | 普通用户，可管理知识库、上传文档、对话 |
| VIEWER | 只读用户，仅可查看和对话 |
