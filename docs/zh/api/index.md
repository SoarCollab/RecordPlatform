# API 参考

RecordPlatform REST API 文档。

## 交互式文档

- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **OpenAPI 规范**: http://localhost:8000/record-platform/v3/api-docs

Swagger Basic 认证（Knife4j）：`admin` / `123456`（默认；可配置）

## 基础 URL

```
http://localhost:8000/record-platform
```

## 认证

除 `/api/v1/auth/**` 和 `/api/v1/share/**` 外，所有端点都需要 JWT 认证。

### 获取 Token

登录功能通过 Spring Security 处理（`/api/v1/auth/login`），不在 AuthorizeController 中。

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "<你的用户名>",
  "password": "<你的密码>"
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
| GET | `/api/v1/auth/ask-code` | 请求邮件验证码 |
| POST | `/api/v1/auth/register` | 用户注册 |
| POST | `/api/v1/auth/reset-confirm` | 密码重置确认 |
| POST | `/api/v1/auth/reset-password` | 执行密码重置 |
| POST | `/api/v1/auth/refresh` | 刷新访问令牌 |
| POST | `/api/v1/auth/sse-token` | 获取 SSE 短期令牌 |

> **注意**：登录功能通过 Spring Security 处理，用户信息获取在 `/api/v1/users/info`

#### 用户注册

```http
POST /api/v1/auth/register
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
| POST | `/api/v1/files/upload/start` | 开始分块上传 |
| POST | `/api/v1/files/upload/chunk` | 上传文件块 |
| POST | `/api/v1/files/upload/complete` | 完成分块上传 |
| POST | `/api/v1/files/upload/pause` | 暂停上传 |
| POST | `/api/v1/files/upload/resume` | 恢复上传 |
| POST | `/api/v1/files/upload/cancel` | 取消上传 |
| GET | `/api/v1/files/upload/check` | 检查上传状态 |
| GET | `/api/v1/files/upload/progress` | 获取上传进度 |
| GET | `/api/v1/files/{id}` | 根据 ID 获取文件详情 |
| GET | `/api/v1/files/byHash` | 通过哈希获取文件（查询参数：hash）|
| GET | `/api/v1/files/list` | 获取用户文件列表 |
| GET | `/api/v1/files/page` | 分页获取用户文件 |
| GET | `/api/v1/files/address` | 获取文件下载地址 |
| GET | `/api/v1/files/decryptInfo` | 获取文件解密信息 |
| GET | `/api/v1/files/download` | 下载文件（查询参数：fileHash）|
| DELETE | `/api/v1/files/delete` | 批量删除文件（查询参数：identifiers；支持文件 hash 或文件 id）|

### 图片

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/images/upload/avatar` | 上传用户头像 |
| POST | `/api/v1/images/upload/image` | 上传图片 |
| GET | `/api/v1/images/download/images/**` | 下载图片 |

### 文件分享

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/share/{shareCode}/info` | 获取分享详情（公开，无需认证）|
| POST | `/api/v1/files/share` | 创建分享链接 |
| PUT | `/api/v1/files/share` | 更新分享（shareCode 在请求体中）|
| DELETE | `/api/v1/files/share/{shareCode}` | 取消分享 |
| GET | `/api/v1/files/shares` | 获取用户分享列表 |

#### 获取分享详情（公开）

公开端点 - 无需认证。

```http
GET /api/v1/share/{shareCode}/info
```

返回分享详情（包含文件列表）。使用业务码表示分享状态：

| 业务码 | 含义 |
|--------|------|
| 200 | 成功 |
| 50009 | 分享不存在 |
| 50010 | 分享已取消 |
| 50011 | 分享已过期 |

响应：
```json
{
  "code": 200,
  "data": {
    "shareCode": "abc123",
    "shareType": 0,
    "expireTime": "2025-01-15T00:00:00Z",
    "files": [
      {
        "fileId": "...",
        "fileName": "document.pdf",
        "fileSize": 1024000
      }
    ]
  }
}
```

### 管理（文件管理）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/files` | 获取所有文件列表（分页）|
| GET | `/api/v1/admin/files/{id}` | 获取文件详情（按 ID）|
| PUT | `/api/v1/admin/files/{id}/status` | 更新文件状态 |
| DELETE | `/api/v1/admin/files/{id}` | 强制删除文件 |
| GET | `/api/v1/admin/files/shares` | 获取所有分享列表 |
| DELETE | `/api/v1/admin/files/shares/{shareCode}` | 强制取消分享 |
| GET | `/api/v1/admin/files/shares/{shareCode}/logs` | 分享访问日志 |
| GET | `/api/v1/admin/files/shares/{shareCode}/stats` | 分享访问统计 |

### 权限管理（仅管理员）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/permissions` | 获取权限树 |
| GET | `/api/v1/system/permissions/list` | 获取权限列表（分页）|
| GET | `/api/v1/system/permissions/modules` | 获取所有模块名列表 |
| POST | `/api/v1/system/permissions` | 创建权限 |
| PUT | `/api/v1/system/permissions/{id}` | 更新权限 |
| DELETE | `/api/v1/system/permissions/{id}` | 删除权限 |
| GET | `/api/v1/system/permissions/roles/{role}` | 获取角色的权限列表 |
| POST | `/api/v1/system/permissions/roles/{role}/grant` | 为角色授予权限 |
| DELETE | `/api/v1/system/permissions/roles/{role}/revoke` | 撤销角色权限（查询参数：permissionCode）|

### 工单

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/tickets` | 创建支持工单 |
| GET | `/api/v1/tickets` | 获取用户工单列表 |
| GET | `/api/v1/tickets/{id}` | 获取工单详情 |
| POST | `/api/v1/tickets/{id}/reply` | 回复工单 |
| PUT | `/api/v1/tickets/{id}` | 更新工单 |
| POST | `/api/v1/tickets/{id}/close` | 关闭工单 |
| POST | `/api/v1/tickets/{id}/confirm` | 确认完成 |
| GET | `/api/v1/tickets/admin/list` | 获取所有工单列表（管理员）|
| PUT | `/api/v1/tickets/admin/{id}/assign` | 分配处理人（管理员）|
| PUT | `/api/v1/tickets/admin/{id}/status` | 更新工单状态（管理员）|

#### 创建工单

```http
POST /api/v1/tickets
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
POST /api/v1/tickets/{id}/reply
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
| GET | `/api/v1/friend-shares/received` | 获取收到的好友分享 |
| GET | `/api/v1/friend-shares/sent` | 获取发送的好友分享 |
| GET | `/api/v1/friend-shares/{shareId}` | 获取分享详情 |
| POST | `/api/v1/friend-shares/{shareId}/read` | 标记分享已读 |
| DELETE | `/api/v1/friend-shares/{shareId}` | 取消分享 |
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

### 会话

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/conversations` | 获取会话列表（分页）|
| GET | `/api/v1/conversations/{id}` | 获取会话详情及消息列表 |
| GET | `/api/v1/conversations/unread-count` | 获取未读会话数 |
| DELETE | `/api/v1/conversations/{id}` | 删除会话 |

### 消息

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/messages` | 发送私信 |
| POST | `/api/v1/messages/to/{receiverId}` | 发送私信（按接收者 ID）|
| GET | `/api/v1/messages/unread-count` | 获取未读私信总数 |

### 公告

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/announcements/latest` | 获取最新公告 |
| GET | `/api/v1/announcements` | 获取公告列表（分页）|
| GET | `/api/v1/announcements/{id}` | 获取公告详情 |
| GET | `/api/v1/announcements/unread-count` | 获取未读公告数 |
| POST | `/api/v1/announcements/{id}/read` | 标记公告已读 |
| POST | `/api/v1/announcements` | 发布公告（管理员）|
| PUT | `/api/v1/announcements/{id}` | 更新公告（管理员）|

### 用户账户

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/users/info` | 获取用户信息 |
| PUT | `/api/v1/users/info` | 更新用户信息 |
| POST | `/api/v1/users/modify-email` | 修改邮箱地址 |
| POST | `/api/v1/users/change-password` | 修改密码 |

### SSE（服务器推送事件）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/sse/connect` | 订阅事件 |
| DELETE | `/api/v1/sse/disconnect` | 断开 SSE 连接 |
| GET | `/api/v1/sse/status` | 检查连接状态 |

### 系统监控（仅管理员）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/stats` | 获取系统统计（用户数、文件数、存储用量、交易数）|
| GET | `/api/v1/system/chain-status` | 获取区块链状态（区块高度、节点数、链类型）|
| GET | `/api/v1/system/health` | 获取系统健康状态（数据库、Redis、区块链、存储）|
| GET | `/api/v1/system/monitor` | 获取聚合监控指标 |

### 系统审计（仅管理员）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/audit/overview` | 获取审计概览数据 |
| GET | `/api/v1/system/audit/logs/page` | 分页查询审计日志（GET 参数）|
| POST | `/api/v1/system/audit/logs/page` | 分页查询审计日志（POST Body）|
| GET | `/api/v1/system/audit/logs/{id}` | 获取日志详情 |
| POST | `/api/v1/system/audit/logs/export` | 导出审计日志到 Excel |
| GET | `/api/v1/system/audit/high-frequency` | 获取高频操作记录 |
| POST | `/api/v1/system/audit/sensitive/page` | 获取敏感操作记录（分页）|
| GET | `/api/v1/system/audit/error-stats` | 获取错误操作统计 |
| GET | `/api/v1/system/audit/time-distribution` | 获取用户时间分布 |
| GET | `/api/v1/system/audit/configs` | 获取审计配置 |
| PUT | `/api/v1/system/audit/configs` | 更新审计配置 |
| GET | `/api/v1/system/audit/check-anomalies` | 检查操作异常 |
| POST | `/api/v1/system/audit/backup-logs` | 备份历史日志 |

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

#### 完成上传

所有分块上传完成后：
```http
POST /api/v1/files/upload/complete
Content-Type: application/x-www-form-urlencoded

clientId=abc123...
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

> **注意**：原生 EventSource 不支持自定义请求头。平台使用通过查询参数传递的短期令牌来建立认证的 SSE 连接。

事件类型（kebab-case 格式）：
- `message-received` - 新私信
- `announcement-published` - 新系统公告
- `ticket-updated` - 工单状态更新或回复
- `friend-request` - 收到新好友请求
- `friend-accepted` - 好友请求被接受
- `friend-share` - 好友分享了文件给你
- `heartbeat` - 连接心跳
- `connected` - 连接建立成功
