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

#### 用户注册

```http
POST /auth/register
Content-Type: application/json

{
  "email": "user@example.com",
  "code": "123456",
  "username": "user1",
  "password": "securepass",
  "nickname": "显示名称"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| email | string | 是 | 用户邮箱地址 |
| code | string | 是 | 邮箱验证码 |
| username | string | 是 | 登录用户名 |
| password | string | 是 | 用户密码 |
| nickname | string | 否 | 显示名称（最大 50 字符）|

### 文件

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/file/upload` | 上传文件（multipart） |
| GET | `/file/list` | 列出用户文件 |
| GET | `/file/{id}` | 获取文件详情 |
| GET | `/file/download/{id}` | 下载文件 |
| DELETE | `/file/{id}` | 删除文件 |
| GET | `/file/search` | 搜索文件 |

### 图片

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/images/upload/avatar` | 上传用户头像 |
| POST | `/images/upload/image` | 上传通用图片 |
| GET | `/images/download/**` | 下载图片 |

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
| GET | `/admin/files/{id}` | 文件详情（含溯源链） |
| PUT | `/admin/files/{id}/status` | 更新文件状态 |
| DELETE | `/admin/files/{id}` | 强制删除文件 |
| GET | `/admin/files/shares` | 列出所有分享 |
| DELETE | `/admin/files/shares/{code}` | 强制取消分享 |
| GET | `/admin/files/shares/{code}/logs` | 分享访问日志 |
| GET | `/admin/files/shares/{code}/stats` | 分享访问统计 |
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
| GET | `/ticket/admin/list` | 列出所有工单（管理员）|
| PUT | `/ticket/admin/{id}/assign` | 分配处理人（管理员）|
| PUT | `/ticket/admin/{id}/status` | 更新状态（管理员）|

#### 创建工单

```http
POST /ticket
Content-Type: application/json

{
  "title": "无法上传大文件",
  "content": "上传超过1GB的文件时，上传失败...",
  "priority": 1,
  "category": 0,
  "attachmentIds": ["file-id-1", "file-id-2"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 是 | 工单标题（最大 200 字符）|
| content | string | 是 | 工单描述 |
| priority | int | 否 | 0=低, 1=中（默认）, 2=高 |
| category | int | 否 | 见下方类别表（默认：99）|
| attachmentIds | string[] | 否 | 关联文件 ID |

#### 工单状态

| 代码 | 状态 | 说明 |
|------|------|------|
| 0 | PENDING | 待处理 |
| 1 | PROCESSING | 处理中 |
| 2 | CONFIRMING | 待用户确认 |
| 3 | COMPLETED | 已解决 |
| 4 | CLOSED | 已关闭 |

#### 工单优先级

| 代码 | 优先级 |
|------|--------|
| 0 | 低 |
| 1 | 中 |
| 2 | 高 |

#### 工单类别

| 代码 | 类别 |
|------|------|
| 0 | Bug 报告 |
| 1 | 功能请求 |
| 2 | 问题咨询 |
| 3 | 反馈建议 |
| 99 | 其他 |

#### 回复工单

```http
POST /ticket/{id}/reply
Content-Type: application/json

{
  "content": "感谢您的反馈，我们正在调查...",
  "attachmentIds": []
}
```

### 好友

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/friends/requests` | 发送好友请求 |
| GET | `/api/v1/friends/requests/received` | 获取收到的请求 |
| GET | `/api/v1/friends/requests/sent` | 获取发送的请求 |
| POST | `/api/v1/friends/requests/{id}/accept` | 接受好友请求 |
| POST | `/api/v1/friends/requests/{id}/reject` | 拒绝好友请求 |
| DELETE | `/api/v1/friends/requests/{id}` | 取消好友请求 |
| GET | `/api/v1/friends/requests/pending-count` | 获取待处理请求数量 |
| GET | `/api/v1/friends` | 获取好友列表（分页）|
| GET | `/api/v1/friends/all` | 获取所有好友（选择器）|
| DELETE | `/api/v1/friends/{id}` | 解除好友关系 |
| PUT | `/api/v1/friends/{id}/remark` | 更新好友备注 |
| GET | `/api/v1/friends/search` | 搜索用户 |

#### 发送好友请求

```http
POST /api/v1/friends/requests
Content-Type: application/json

{
  "addresseeId": "user-external-id",
  "message": "你好，交个朋友吧！"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| addresseeId | string | 是 | 目标用户的外部 ID |
| message | string | 否 | 请求附言（最大 255 字符）|

#### 好友请求状态

| 代码 | 状态 | 说明 |
|------|------|------|
| 0 | PENDING | 待处理 |
| 1 | ACCEPTED | 已接受 |
| 2 | REJECTED | 已拒绝 |
| 3 | CANCELLED | 已取消 |

### 好友文件分享

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/friend-shares` | 分享文件给好友 |
| GET | `/api/v1/friend-shares/received` | 获取收到的分享 |
| GET | `/api/v1/friend-shares/sent` | 获取发送的分享 |
| GET | `/api/v1/friend-shares/{id}` | 获取分享详情 |
| POST | `/api/v1/friend-shares/{id}/read` | 标记为已读 |
| DELETE | `/api/v1/friend-shares/{id}` | 取消分享 |
| GET | `/api/v1/friend-shares/unread-count` | 获取未读数量 |

#### 分享文件给好友

```http
POST /api/v1/friend-shares
Content-Type: application/json

{
  "friendId": "friend-external-id",
  "fileHashes": ["hash1", "hash2"],
  "message": "看看这些文件！"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| friendId | string | 是 | 好友的外部 ID |
| fileHashes | string[] | 是 | 要分享的文件哈希数组 |
| message | string | 否 | 分享附言（最大 255 字符）|

### SSE（服务器推送事件）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/sse/connect` | 订阅事件 |
| DELETE | `/api/v1/sse/disconnect` | 断开 SSE 连接 |
| GET | `/api/v1/sse/status` | 检查连接状态 |

> **注意**：完整的 REST API 文档（包含所有端点、请求/响应示例和详细字段描述）请参阅项目根目录的 [API_DOCUMENTATION.md](https://github.com/SoarCollab/RecordPlatform/blob/main/API_DOCUMENTATION.md)。

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

对于大文件（>10MB），使用动态分块上传：

#### 动态分块大小策略

系统根据文件大小自动选择最优分块大小以提高性能：

| 文件大小 | 推荐分块大小 |
|----------|--------------|
| < 10MB   | 2MB          |
| < 100MB  | 5MB          |
| < 500MB  | 10MB         |
| < 2GB    | 20MB         |
| >= 2GB   | 50MB         |

**最大分块限制：80MB**（Dubbo 载荷限制为 100MB）

#### 开始分块上传

```http
POST /file/upload/start
Content-Type: application/json

{
  "fileName": "large-file.zip",
  "fileSize": 1073741824,
  "contentType": "application/zip",
  "chunkSize": 20971520,
  "totalChunks": 52
}
```

#### 上传分块

```http
POST /file/upload/chunk
Content-Type: multipart/form-data

chunk: <binary>
chunkNumber: 1
totalChunks: 10
fileHash: abc123...
```

#### 合并分块

所有分块上传完成后：
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
const eventSource = new EventSource('/record-platform/api/v1/sse/connect', {
  headers: { 'Authorization': 'Bearer <token>' }
});

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log(data.type, data.payload);
};
```

事件类型（kebab-case 格式）：
- `message-received` - 新私信
- `announcement-published` - 新系统公告
- `ticket-updated` - 工单状态更新或回复
- `friend-request` - 收到新好友请求
- `friend-accepted` - 好友请求被接受
- `friend-share` - 好友分享了文件给你
- `heartbeat` - 连接心跳
- `connected` - 连接建立成功

