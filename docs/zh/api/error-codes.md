# 错误码参考

本页面记录 RecordPlatform API 返回的所有错误码。

## 错误码范围

| 范围 | 类别 |
|------|------|
| 200 | 成功 |
| 10000-19999 | 参数校验错误 |
| 20000-29999 | 用户/认证错误 |
| 30000-39999 | 外部服务错误（区块链、存储）|
| 40000-49999 | 系统错误 |
| 50000-59999 | 业务数据错误 |
| 60000-69999 | 消息服务错误 |
| 70000-79999 | 权限错误 |

## 成功

| 代码 | 名称 | 说明 |
|------|------|------|
| 200 | SUCCESS | 操作成功 |

## 参数错误（10000-19999）

| 代码 | 名称 | 说明 |
|------|------|------|
| 10000 | PARAM_ERROR | 参数错误（通用）|
| 10001 | PARAM_IS_INVALID | 参数无效 |
| 10002 | PARAM_IS_BLANK | 参数为空 |
| 10003 | PARAM_TYPE_BIND_ERROR | 参数格式错误 |
| 10004 | PARAM_NOT_COMPLETE | 参数缺失 |
| 10005 | JSON_PARSE_ERROR | JSON 解析失败 |

## 用户/认证错误（20000-29999）

| 代码 | 名称 | 说明 |
|------|------|------|
| 20001 | USER_NOT_LOGGED_IN | 用户未登录 |
| 20002 | USER_LOGIN_ERROR | 账号不存在或密码错误 |
| 20003 | USER_ACCOUNT_FORBIDDEN | 账号已被禁用 |
| 20004 | USER_NOT_EXIST | 用户不存在 |
| 20005 | USER_HAS_EXISTED | 用户已存在 |
| 20006 | USER_ACCOUNT_LOCKED | 账户已被临时锁定（登录失败次数过多）|

## 外部服务错误（30000-39999）

| 代码 | 名称 | 说明 |
|------|------|------|
| 30001 | CONTRACT_ERROR | 合约调用失败 |
| 30002 | INVALID_RETURN_VALUE | 合约返回值错误 |
| 30003 | GET_USER_FILE_ERROR | 获取用户文件失败 |
| 30004 | DELETE_USER_FILE_ERROR | 删除用户文件失败 |
| 30005 | GET_USER_SHARE_FILE_ERROR | 获取分享文件失败 |
| 30006 | BLOCKCHAIN_ERROR | 区块链服务请求失败 |
| 30007 | TRANSACTION_NOT_FOUND | 交易记录未找到 |
| 30008 | TRANSACTION_RECEIPT_NOT_FOUND | 交易回执未找到 |
| 30009 | FILE_SERVICE_ERROR | 文件服务请求失败 |
| 30010 | SERVICE_CIRCUIT_OPEN | 服务暂时不可用（熔断）|
| 30011 | SERVICE_TIMEOUT | 服务响应超时 |
| 30012 | STORAGE_QUORUM_NOT_REACHED | 存储写入未达到仲裁要求 |
| 30013 | STORAGE_INSUFFICIENT_REPLICAS | 可用存储节点不足 |
| 30014 | STORAGE_DEGRADED_WRITE | 存储以降级模式写入，节点恢复后自动同步 |

## 系统错误（40000-49999）

| 代码 | 名称 | 说明 |
|------|------|------|
| 40001 | FILE_MAX_SIZE_OVERFLOW | 上传尺寸过大 |
| 40002 | FILE_ACCEPT_NOT_SUPPORT | 不支持的文件格式 |
| 40003 | SYSTEM_BUSY | 系统繁忙，请稍后重试 |
| 40004 | RATE_LIMIT_EXCEEDED | 请求过于频繁 |
| 40005 | SERVICE_UNAVAILABLE | 服务暂时不可用 |

## 业务数据错误（50000-59999）

| 代码 | 名称 | 说明 |
|------|------|------|
| 50001 | RESULT_DATA_NONE | 数据未找到 |
| 50002 | DATA_IS_WRONG | 数据有误 |
| 50003 | DATA_ALREADY_EXISTED | 数据已存在 |
| 50004 | AUTH_CODE_ERROR | 验证码错误 |
| 50005 | FILE_UPLOAD_ERROR | 文件上传失败 |
| 50006 | FILE_DOWNLOAD_ERROR | 文件下载失败 |
| 50007 | FILE_DELETE_ERROR | 文件删除失败 |
| 50008 | FILE_NOT_EXIST | 文件不存在 |
| 50009 | FILE_EMPTY | 文件为空 |
| 50010 | FILE_RECORD_ERROR | 文件存证失败 |
| 50011 | SHARE_CANCELLED | 分享链接已被取消 |
| 50012 | SHARE_EXPIRED | 分享已过期 |

## 消息服务错误（60000-69999）

| 代码 | 名称 | 说明 |
|------|------|------|
| 60001 | MESSAGE_NOT_FOUND | 消息不存在 |
| 60002 | CONVERSATION_NOT_FOUND | 会话不存在 |
| 60003 | CANNOT_MESSAGE_SELF | 不能给自己发送消息 |
| 60004 | ANNOUNCEMENT_NOT_FOUND | 公告不存在 |
| 60005 | TICKET_NOT_FOUND | 工单不存在 |
| 60006 | TICKET_ALREADY_CLOSED | 工单已关闭，无法操作 |
| 60007 | TICKET_NOT_OWNER | 无权操作此工单 |
| 60008 | INVALID_TICKET_STATUS | 工单状态无效 |
| 60009 | ATTACHMENT_LIMIT_EXCEEDED | 附件数量超过限制 |

## 好友系统错误（60010-60019）

| 代码 | 名称 | 说明 |
|------|------|------|
| 60010 | NOT_FRIENDS | 你们还不是好友，无法发送消息 |
| 60011 | FRIEND_REQUEST_EXISTS | 已发送过好友请求，请等待对方处理 |
| 60012 | ALREADY_FRIENDS | 你们已经是好友了 |
| 60013 | CANNOT_ADD_SELF | 不能添加自己为好友 |
| 60014 | FRIEND_REQUEST_NOT_FOUND | 好友请求不存在 |
| 60015 | FRIEND_REQUEST_PROCESSED | 好友请求已处理 |
| 60016 | FRIEND_SHARE_NOT_FOUND | 好友分享不存在 |
| 60017 | FRIEND_SHARE_UNAUTHORIZED | 无权操作此好友分享 |

## 权限错误（70000-79999）

| 代码 | 名称 | 说明 |
|------|------|------|
| 70001 | PERMISSION_UNAUTHENTICATED | 此操作需要登录系统 |
| 70002 | PERMISSION_UNAUTHORIZED | 权限不足，无权操作 |
| 70004 | PERMISSION_TOKEN_EXPIRED | Token 已过期 |
| 70005 | PERMISSION_LIMIT | 访问次数受限制 |
| 70006 | PERMISSION_TOKEN_INVALID | 无效 Token |
| 70007 | PERMISSION_SIGNATURE_ERROR | 签名验证失败 |

## 错误响应格式

所有错误响应遵循此结构：

```json
{
  "code": 50008,
  "message": "文件不存在",
  "data": null
}
```

## 错误处理

### 可重试错误

以下错误可以使用指数退避策略重试：

- `30010` SERVICE_CIRCUIT_OPEN
- `30011` SERVICE_TIMEOUT
- `40003` SYSTEM_BUSY
- `40004` RATE_LIMIT_EXCEEDED
- `40005` SERVICE_UNAVAILABLE

### Token 刷新

收到 `70004`（TOKEN_EXPIRED）时，客户端应该：

1. 调用刷新 Token 端点（如果可用）
2. 或重定向到登录页面

### 存储降级模式

`30014`（STORAGE_DEGRADED_WRITE）表示写入成功，但副本数少于配置值。系统会在节点恢复后自动同步。这不是失败，而是警告。
