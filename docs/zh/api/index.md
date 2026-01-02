# API 参考

RecordPlatform REST API 文档。

## 交互式文档

- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **OpenAPI 规范**: http://localhost:8000/record-platform/v3/api-docs

默认凭据：`admin` / `123456`

## 基础 URL

```
http://localhost:8000/record-platform
```

## 认证

除 `/auth/**` 外，所有端点都需要 JWT 认证。

### 获取 Token

```http
POST /auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "123456"
}
```

响应：
```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzUxMiJ9...",
    "expiresIn": 86400
  }
}
```

### 使用 Token

在所有后续请求中包含：
```http
Authorization: Bearer <token>
```

## API 端点

### 认证

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/auth/login` | 用户登录 |
| POST | `/auth/register` | 用户注册 |
| POST | `/auth/logout` | 用户登出 |
| GET | `/auth/me` | 获取当前用户信息 |

### 文件

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/file/upload` | 上传文件（multipart） |
| GET | `/file/list` | 列出用户文件 |
| GET | `/file/{id}` | 获取文件详情 |
| GET | `/file/download/{id}` | 下载文件 |
| DELETE | `/file/{id}` | 删除文件 |
| GET | `/file/search` | 搜索文件 |

### 文件分享

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/share/create` | 创建分享链接 |
| GET | `/share/{code}` | 访问分享文件 |
| GET | `/share/list` | 列出用户分享 |
| DELETE | `/share/{id}` | 撤销分享 |
| GET | `/share/{id}/logs` | 获取分享访问日志 |

### 管理

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/admin/files` | 列出所有文件（管理员） |
| GET | `/admin/users` | 列出所有用户 |
| PUT | `/admin/user/{id}/role` | 更新用户角色 |
| GET | `/admin/audit/logs` | 获取审计日志 |
| GET | `/admin/system/monitor` | 系统指标 |

### 工单

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/ticket` | 创建支持工单 |
| GET | `/ticket/list` | 列出用户工单 |
| GET | `/ticket/{id}` | 获取工单详情 |
| POST | `/ticket/{id}/reply` | 回复工单 |
| PUT | `/ticket/{id}/status` | 更新工单状态 |

### SSE（服务器推送事件）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/sse/subscribe` | 订阅事件 |

## 响应格式

所有响应遵循此结构：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

### 状态码

| 代码 | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 请求错误 |
| 401 | 未授权 |
| 403 | 禁止访问 |
| 404 | 未找到 |
| 429 | 请求过于频繁 |
| 500 | 服务器错误 |

### 错误响应

```json
{
  "code": 400,
  "message": "验证失败",
  "data": null
}
```

## 分页

列表端点支持分页：

```http
GET /file/list?page=1&size=20&sort=createdAt,desc
```

响应包含分页信息：
```json
{
  "code": 200,
  "data": {
    "records": [...],
    "total": 100,
    "current": 1,
    "size": 20,
    "pages": 5
  }
}
```

## 文件上传

### 单文件上传

```http
POST /file/upload
Content-Type: multipart/form-data

file: <binary>
```

### 分块上传

对于大文件（>10MB），使用分块上传：

```http
POST /file/upload/chunk
Content-Type: multipart/form-data

chunk: <binary>
chunkNumber: 1
totalChunks: 10
fileHash: abc123...
```

所有块完成后：
```http
POST /file/upload/merge
Content-Type: application/json

{
  "fileHash": "abc123...",
  "fileName": "large-file.zip",
  "totalChunks": 10
}
```

## 速率限制

API 请求按用户限速：

| 端点类别 | 限制 |
|----------|------|
| 认证 | 10次/分钟 |
| 文件上传 | 20次/分钟 |
| 文件下载 | 100次/分钟 |
| 其他 | 60次/分钟 |

速率限制响应头：
```http
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 55
X-RateLimit-Reset: 1609459200
```

## Dubbo 服务

内部服务使用 Dubbo Triple 协议（非 REST）。

### BlockChainService

```java
// 存储文件到区块链
SharingVO storeFile(String fileHash, String fileKey, String fileName);

// 查询区块链记录
SharingVO queryFile(String txHash);

// 删除区块链记录
void deleteFile(String txHash);
```

### DistributedStorageService

```java
// 上传文件块
void uploadChunk(String bucket, String objectKey, byte[] data);

// 下载文件
byte[] downloadFile(String bucket, String objectKey);

// 删除文件
void deleteFile(String bucket, String objectKey);

// 检查文件是否存在
boolean exists(String bucket, String objectKey);
```

## WebSocket 事件（SSE）

订阅实时事件：

```javascript
const eventSource = new EventSource('/record-platform/sse/subscribe', {
  headers: { 'Authorization': 'Bearer <token>' }
});

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log(data.type, data.payload);
};
```

事件类型：
- `FILE_UPLOADED` - 文件上传完成
- `FILE_DELETED` - 文件已删除
- `SHARE_ACCESSED` - 分享链接被访问
- `ANNOUNCEMENT` - 系统公告
- `TICKET_REPLY` - 工单回复

