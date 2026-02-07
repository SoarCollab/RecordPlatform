# API 参考

RecordPlatform REST API 索引文档（与当前 Controller 和安全配置对齐）。

## 交互式文档

- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **OpenAPI 规范**: http://localhost:8000/record-platform/v3/api-docs

Swagger Basic 认证（Knife4j）：`admin` / `123456`（默认，可配置）。

## 基础 URL

```text
http://localhost:8000/record-platform
```

## 认证与公开规则

### 1) 常规认证

大多数端点需要 JWT：

```http
Authorization: Bearer <token>
```

### 2) 明确公开端点（`permitAll`）

根据 `SecurityConfiguration`：

- `POST /api/v1/auth/login`
- `GET /api/v1/auth/ask-code`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/reset-confirm`
- `POST /api/v1/auth/reset-password`
- `GET /api/v1/files/getSharingFiles`
- `GET /api/v1/files/public/download`
- `GET /api/v1/files/public/decryptInfo`
- `GET /api/v1/images/download/images/**`
- `GET /api/v1/share/**`
- `GET /api/v1/sse/connect`（需短期令牌，见下文）

### 3) SSE 双令牌模式

- `POST /api/v1/auth/sse-token`：需要常规 JWT（登录态）
- `GET /api/v1/sse/connect?token=...`：公开路由，但必须携带有效一次性短期令牌

## API 端点（按模块）

### 认证（`/api/v1/auth`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/auth/ask-code` | 请求邮件验证码 |
| POST | `/api/v1/auth/register` | 用户注册 |
| POST | `/api/v1/auth/reset-confirm` | 密码重置确认 |
| POST | `/api/v1/auth/reset-password` | 执行密码重置 |
| POST | `/api/v1/auth/refresh` | 刷新访问令牌 |
| POST | `/api/v1/auth/sse-token` | 获取 SSE 短期令牌（需 JWT） |

> 登录接口由 Spring Security 处理：`POST /api/v1/auth/login`

### 用户（`/api/v1/users`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/users/info` | 获取用户信息 |
| PUT | `/api/v1/users/info` | 更新用户信息 |
| POST | `/api/v1/users/modify-email` | 修改邮箱 |
| POST | `/api/v1/users/change-password` | 修改密码 |

### 文件上传（`/api/v1/files/upload`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/files/upload/start` | 开始分片上传 |
| POST | `/api/v1/files/upload/chunk` | 上传分片 |
| POST | `/api/v1/files/upload/complete` | 完成上传 |
| POST | `/api/v1/files/upload/pause` | 暂停上传 |
| POST | `/api/v1/files/upload/resume` | 恢复上传 |
| POST | `/api/v1/files/upload/cancel` | 取消上传 |
| GET | `/api/v1/files/upload/check` | 检查上传状态 |
| GET | `/api/v1/files/upload/progress` | 查询上传进度 |

### 文件与分享（`/api/v1/files`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/files/{id}` | 按文件 ID 查询详情 |
| GET | `/api/v1/files/byHash` | 按文件哈希查询详情（支持 `fileHash/hash`）|
| GET | `/api/v1/files/list` | 用户文件列表 |
| GET | `/api/v1/files/page` | 用户文件分页 |
| GET | `/api/v1/files/stats` | 用户文件统计 |
| GET | `/api/v1/files/address` | 获取下载地址 |
| GET | `/api/v1/files/getTransaction` | 查询链上交易信息 |
| GET | `/api/v1/files/download` | 下载文件（登录态） |
| GET | `/api/v1/files/decryptInfo` | 获取解密信息（登录态） |
| GET | `/api/v1/files/getSharingFiles` | 公开分享文件列表（公开） |
| GET | `/api/v1/files/shares` | 获取我的分享列表 |
| DELETE | `/api/v1/files/delete` | 批量删除（支持 hash/id） |
| DELETE | `/api/v1/files/deleteById` | 按文件 ID 删除 |
| POST | `/api/v1/files/share` | 创建分享 |
| PUT | `/api/v1/files/share` | 更新分享 |
| DELETE | `/api/v1/files/share/{shareCode}` | 取消分享 |
| POST | `/api/v1/files/saveShareFile` | 保存分享文件到我的文件 |
| GET | `/api/v1/files/share/download` | 登录态分享下载 |
| GET | `/api/v1/files/share/decryptInfo` | 登录态分享解密信息 |
| GET | `/api/v1/files/share/{shareCode}/access-logs` | 分享访问日志（管理员） |
| GET | `/api/v1/files/share/{shareCode}/stats` | 分享访问统计（管理员） |
| GET | `/api/v1/files/{id}/provenance` | 文件溯源链路（管理员） |
| GET | `/api/v1/files/public/download` | 公开分享下载（公开） |
| GET | `/api/v1/files/public/decryptInfo` | 公开分享解密信息（公开） |

### 管理员文件审计（`/api/v1/admin/files`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/files` | 获取所有文件（分页） |
| GET | `/api/v1/admin/files/{id}` | 文件详情（含审计信息） |
| PUT | `/api/v1/admin/files/{id}/status` | 更新文件状态 |
| DELETE | `/api/v1/admin/files/{id}` | 强制物理删除文件 |
| GET | `/api/v1/admin/files/shares` | 获取所有分享（分页） |
| DELETE | `/api/v1/admin/files/shares/{shareCode}` | 强制取消分享 |
| GET | `/api/v1/admin/files/shares/{shareCode}/logs` | 分享访问日志 |
| GET | `/api/v1/admin/files/shares/{shareCode}/stats` | 分享访问统计 |

### 公开分享页（`/api/v1/share`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/share/{shareCode}/info` | 获取分享详情（公开） |

### 图片（`/api/v1/images`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/images/upload/avatar` | 上传头像 |
| POST | `/api/v1/images/upload/image` | 上传图片 |
| GET | `/api/v1/images/download/images/**` | 下载图片（公开） |

### 好友（`/api/v1/friends`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/friends/requests` | 发送好友请求 |
| GET | `/api/v1/friends/requests/received` | 收到的请求 |
| GET | `/api/v1/friends/requests/sent` | 发出的请求 |
| POST | `/api/v1/friends/requests/{requestId}/accept` | 接受好友请求 |
| POST | `/api/v1/friends/requests/{requestId}/reject` | 拒绝好友请求 |
| DELETE | `/api/v1/friends/requests/{requestId}` | 取消好友请求 |
| GET | `/api/v1/friends/requests/pending-count` | 待处理好友请求数 |
| GET | `/api/v1/friends` | 好友列表（分页） |
| GET | `/api/v1/friends/all` | 全量好友列表 |
| DELETE | `/api/v1/friends/{friendId}` | 解除好友关系 |
| PUT | `/api/v1/friends/{friendId}/remark` | 更新好友备注 |
| GET | `/api/v1/friends/search` | 搜索用户 |

### 好友文件分享（`/api/v1/friend-shares`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/friend-shares` | 分享文件给好友 |
| GET | `/api/v1/friend-shares/received` | 收到的好友分享 |
| GET | `/api/v1/friend-shares/sent` | 发出的好友分享 |
| GET | `/api/v1/friend-shares/{shareId}` | 分享详情 |
| POST | `/api/v1/friend-shares/{shareId}/read` | 标记已读 |
| DELETE | `/api/v1/friend-shares/{shareId}` | 取消分享 |
| GET | `/api/v1/friend-shares/unread-count` | 未读数量 |

### 会话（`/api/v1/conversations`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/conversations` | 会话列表（分页） |
| GET | `/api/v1/conversations/{id}` | 会话详情与消息列表 |
| GET | `/api/v1/conversations/unread-count` | 未读会话数 |
| POST | `/api/v1/conversations/{id}/read` | 标记会话已读 |
| DELETE | `/api/v1/conversations/{id}` | 删除会话 |

> `GET /api/v1/conversations/read` 为兼容兜底路由，固定返回 404（防误匹配），非业务接口。

### 消息（`/api/v1/messages`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/messages` | 发送私信 |
| POST | `/api/v1/messages/to/{receiverId}` | 按接收者 ID 发送私信 |
| GET | `/api/v1/messages/unread-count` | 未读私信总数 |

### 公告（`/api/v1/announcements`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/announcements/latest` | 最新公告 |
| GET | `/api/v1/announcements` | 公告列表 |
| GET | `/api/v1/announcements/{id}` | 公告详情 |
| GET | `/api/v1/announcements/unread-count` | 未读公告数 |
| POST | `/api/v1/announcements/{id}/read` | 标记公告已读 |
| POST | `/api/v1/announcements/read-all` | 全部公告标记已读 |
| GET | `/api/v1/announcements/admin/list` | 管理员公告列表 |
| POST | `/api/v1/announcements` | 发布公告（管理员） |
| PUT | `/api/v1/announcements/{id}` | 更新公告（管理员） |
| DELETE | `/api/v1/announcements/{id}` | 删除公告（管理员） |

### 工单（`/api/v1/tickets`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/tickets` | 我的工单列表 |
| GET | `/api/v1/tickets/{id}` | 工单详情 |
| POST | `/api/v1/tickets` | 创建工单 |
| PUT | `/api/v1/tickets/{id}` | 更新工单 |
| POST | `/api/v1/tickets/{id}/reply` | 回复工单 |
| POST | `/api/v1/tickets/{id}/close` | 关闭工单 |
| POST | `/api/v1/tickets/{id}/confirm` | 确认完成 |
| GET | `/api/v1/tickets/pending-count` | 待处理工单数（兼容） |
| GET | `/api/v1/tickets/unread-count` | 未读工单数 |
| GET | `/api/v1/tickets/admin/list` | 管理员工单列表 |
| PUT | `/api/v1/tickets/admin/{id}/assign` | 分配处理人（管理员） |
| PUT | `/api/v1/tickets/admin/{id}/status` | 更新状态（管理员） |
| GET | `/api/v1/tickets/admin/pending-count` | 管理员待处理工单数 |

### 权限（`/api/v1/system/permissions`，管理员）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/permissions` | 权限树 |
| GET | `/api/v1/system/permissions/list` | 权限分页列表 |
| GET | `/api/v1/system/permissions/modules` | 模块列表 |
| POST | `/api/v1/system/permissions` | 创建权限 |
| PUT | `/api/v1/system/permissions/{id}` | 更新权限 |
| DELETE | `/api/v1/system/permissions/{id}` | 删除权限 |
| GET | `/api/v1/system/permissions/roles/{role}` | 查询角色权限 |
| POST | `/api/v1/system/permissions/roles/{role}/grant` | 授权 |
| DELETE | `/api/v1/system/permissions/roles/{role}/revoke` | 撤销授权 |

### 系统监控（`/api/v1/system`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/stats` | 系统统计 |
| GET | `/api/v1/system/chain-status` | 链状态 |
| GET | `/api/v1/system/health` | 健康状态 |
| GET | `/api/v1/system/storage-capacity` | 存储容量聚合（集群/节点/故障域） |
| GET | `/api/v1/system/monitor` | 聚合监控指标 |

### 系统审计（`/api/v1/system/audit`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/audit/overview` | 审计概览 |
| GET | `/api/v1/system/audit/logs/page` | 日志分页（GET） |
| POST | `/api/v1/system/audit/logs/page` | 日志分页（POST） |
| GET | `/api/v1/system/audit/logs/{id}` | 日志详情 |
| POST | `/api/v1/system/audit/logs/export` | 导出审计日志 |
| GET | `/api/v1/system/audit/high-frequency` | 高频操作统计 |
| POST | `/api/v1/system/audit/sensitive/page` | 敏感操作分页 |
| GET | `/api/v1/system/audit/error-stats` | 错误统计 |
| GET | `/api/v1/system/audit/time-distribution` | 时间分布 |
| GET | `/api/v1/system/audit/configs` | 审计配置 |
| PUT | `/api/v1/system/audit/configs` | 更新审计配置 |
| GET | `/api/v1/system/audit/check-anomalies` | 检查异常 |
| POST | `/api/v1/system/audit/backup-logs` | 备份日志 |

### SSE（`/api/v1/sse`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/sse/connect` | 建立 SSE 连接（短期 token） |
| DELETE | `/api/v1/sse/disconnect` | 断开连接 |
| GET | `/api/v1/sse/status` | 查询连接状态 |

推荐接入流程：

```text
1) POST /api/v1/auth/sse-token   (Authorization: Bearer <jwt>)
2) GET  /api/v1/sse/connect?token=<sseToken>&connectionId=<optional>
```

常见事件类型：

- `connected`
- `heartbeat`
- `message-received`
- `file-record-success`
- `file-record-failed`
- `announcement-published`
- `ticket-updated`
- `friend-request`
- `friend-accepted`
- `friend-share`
- `audit-alert`

## 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

- 详细业务错误码请参考：`/docs/zh/api/error-codes.md`
- 更完整字段级说明请参考：`/API_DOCUMENTATION.md`
