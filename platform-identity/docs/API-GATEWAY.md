# API Gateway 接口文档

## 概述

API Gateway 模块提供第三方应用接入平台的 API 管理功能，包括应用注册、密钥管理、权限控制、流量监控等功能。

**服务端口**: `8888`  
**服务路径**: `/identity`  
**完整基础URL**: `http://localhost:8888/identity`

## 认证方式

### 1. Bearer Token 认证（用于管理接口）

管理接口需要用户登录后获取的 Bearer Token：

```http
Authorization: Bearer {token}
```

### 2. API Key 签名认证（用于 API 调用）

API 调用需要使用签名认证，请求头包含：

```http
X-API-Key: ak_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
X-Timestamp: 1697001600
X-Nonce: random_string_123456
X-Signature: sha256_signature_here
```

## API 接口列表

### 应用管理接口

#### 1. 注册应用

**接口地址**: `POST /api/gateway/applications`

**请求头**:
```http
Authorization: Bearer {token}
Content-Type: application/x-www-form-urlencoded
```

**请求参数**:
| 参数名 | 类型 | 必填 | 说明 |
|-------|------|-----|------|
| appName | String | 是 | 应用名称 |
| appDescription | String | 否 | 应用描述 |
| appType | Integer | 是 | 应用类型：1-Web应用, 2-移动应用, 3-服务端应用, 4-其他 |
| appWebsite | String | 否 | 应用官网URL |
| callbackUrl | String | 否 | OAuth回调URL，多个用逗号分隔 |

**请求示例**:
```http
POST /identity/api/gateway/applications
Authorization: Bearer {token}
Content-Type: application/x-www-form-urlencoded

appName=示例应用&appDescription=这是一个测试应用&appType=1&appWebsite=https://example.com&callbackUrl=https://example.com/callback
```

**响应示例**:
```json
{
  "status": 201,
  "message": "Created",
  "data": {
    "app_id": 1845123456789012,
    "app_code": "app_xY3kL9mN2pQ8rS5t",
    "app_name": "示例应用",
    "app_type": 1,
    "app_status": 0,
    "create_time": "2025-10-15T10:30:00"
  },
  "timestamp": "2025-10-15T10:30:00"
}
```

#### 2. 审核应用（管理员）

**接口地址**: `POST /api/gateway/applications/{appId}/approval`

**权限要求**: `api:app:approve`

**请求参数**:
| 参数名 | 类型 | 必填 | 说明 |
|-------|------|-----|------|
| appId | Long | 是 | 应用ID（路径参数） |
| approved | Boolean | 是 | 是否通过审核 |
| rejectReason | String | 否 | 拒绝原因（拒绝时必填） |

**请求示例**:
```http
POST /identity/api/gateway/applications/1845123456789012/approval?approved=true
Authorization: Bearer {admin_token}
```

**响应示例**:
```json
{
  "status": 200,
  "message": "审核成功",
  "data": null,
  "timestamp": "2025-10-15T10:35:00"
}
```

#### 3. 获取我的应用列表

**接口地址**: `GET /api/gateway/applications/my`

**请求头**:
```http
Authorization: Bearer {token}
```

**响应示例**:
```json
{
  "status": 200,
  "message": "OK",
  "data": [
    {
      "id": 1845123456789012,
      "appName": "示例应用",
      "appCode": "app_xY3kL9mN2pQ8rS5t",
      "appStatus": 1,
      "appType": 1,
      "createTime": "2025-10-15T10:30:00",
      "approveTime": "2025-10-15T10:35:00"
    }
  ],
  "timestamp": "2025-10-15T10:40:00"
}
```

#### 4. 更新应用信息

**接口地址**: `PUT /api/gateway/applications/{appId}`

**权限要求**: `api:app:update`

**请求体**:
```http
PUT /identity/api/gateway/applications/1845123456789012
Authorization: Bearer {token}
Content-Type: application/x-www-form-urlencoded

appName=更新后的应用名&appDescription=更新后的描述&appWebsite=https://new-example.com&callbackUrl=https://new-example.com/callback
```

#### 5. 启用/禁用应用

**启用应用**:
```http
POST /identity/api/gateway/applications/{appId}/enable
Authorization: Bearer {token}
```

**禁用应用**:
```http
POST /identity/api/gateway/applications/{appId}/disable?reason=违规使用
Authorization: Bearer {token}
```

#### 6. 设置 IP 白名单

**接口地址**: `PUT /api/gateway/applications/{appId}/whitelist`

**请求体**:
```json
{
  "ipList": [
    "192.168.1.0/24",
    "10.0.0.1",
    "172.16.0.0/16"
  ]
}
```

### 密钥管理接口

#### 1. 生成 API 密钥

**接口地址**: `POST /api/gateway/keys`

**权限要求**: `api:key:generate`

**请求参数**:
| 参数名 | 类型 | 必填 | 说明 |
|-------|------|-----|------|
| appId | Long | 是 | 应用ID |
| keyName | String | 否 | 密钥名称 |
| keyType | Integer | 是 | 密钥类型：1-正式环境, 2-测试环境 |
| expireDays | Integer | 否 | 过期天数，不填表示永久有效 |

**请求示例**:
```http
POST /identity/api/gateway/keys
Authorization: Bearer {token}
Content-Type: application/x-www-form-urlencoded

appId=1845123456789012&keyName=生产环境主密钥&keyType=1&expireDays=365
```

**响应示例**:
```json
{
  "status": 201,
  "message": "Created",
  "data": {
    "key_id": 1845987654321098,
    "app_id": 1845123456789012,
    "api_key": "ak_xY3kL9mN2pQ8rS5tU6vW7xA1bC2dE3f",
    "api_secret": "sk_aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5aB6cD7e",
    "key_name": "生产环境主密钥",
    "key_type": 1,
    "expire_time": "2026-10-15T10:30:00",
    "create_time": "2025-10-15T10:30:00"
  },
  "timestamp": "2025-10-15T10:30:00"
}
```

**⚠️ 注意**: API Secret 只在生成时返回一次，请妥善保存！

#### 2. 获取应用的密钥列表

**接口地址**: `GET /api/gateway/keys/application/{appId}`

**响应示例**:
```json
{
  "status": 200,
  "message": "OK",
  "data": [
    {
      "id": 1845987654321098,
      "appId": 1845123456789012,
      "apiKey": "ak_xY3kL9mN2pQ8rS5tU6vW7xA1bC2dE3f",
      "keyName": "生产环境主密钥",
      "keyStatus": 1,
      "keyType": 1,
      "expireTime": "2026-10-15T10:30:00",
      "lastUsedTime": "2025-10-15T15:20:00",
      "usedCount": 1523
    }
  ],
  "timestamp": "2025-10-15T10:40:00"
}
```

#### 3. 验证 API 密钥

**接口地址**: `POST /api/gateway/keys/validation`

**请求参数**:
| 参数名 | 类型 | 必填 | 说明 |
|-------|------|-----|------|
| apiKey | String | 是 | API密钥 |
| timestamp | Long | 是 | 时间戳（秒） |
| nonce | String | 是 | 随机字符串 |
| signature | String | 是 | 签名 |
| requestData | String | 否 | 请求数据 |

**签名算法**:
```javascript
// JavaScript 示例
const crypto = require('crypto');

function generateSignature(apiKey, apiSecret, timestamp, nonce, requestData) {
  // 1. 构建签名字符串
  const signString = `${apiKey}${timestamp}${nonce}${requestData || ''}`;
  
  // 2. 使用 HMAC-SHA256 计算签名
  const signature = crypto
    .createHmac('sha256', apiSecret)
    .update(signString)
    .digest('hex');
  
  return signature;
}

// 使用示例
const apiKey = 'ak_xY3kL9mN2pQ8rS5tU6vW7xA1bC2dE3f';
const apiSecret = 'sk_aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5aB6cD7e';
const timestamp = Math.floor(Date.now() / 1000);
const nonce = 'unique_' + Math.random().toString(36);
const requestData = '{"data": "需要签名的数据"}';

const signature = generateSignature(apiKey, apiSecret, timestamp, nonce, requestData);
```

**请求示例**:
```http
POST /identity/api/gateway/keys/validation
Content-Type: application/x-www-form-urlencoded

apiKey=ak_xY3kL9mN2pQ8rS5tU6vW7xA1bC2dE3f&timestamp=1697001600&nonce=unique_random_string_123456&signature=2a9f3d8b7e5c1a6d9e2f7b4c8a3e6d9f1b5c7a3e8d2f9a4b6c7e1d3f5a8b2c4e
```

**响应示例**:
```json
{
  "status": 200,
  "message": "验证成功",
  "data": {
    "valid": true,
    "app_id": 1845123456789012,
    "app_name": "示例应用",
    "key_type": 1,
    "remaining_quota": 9500,
    "expire_time": "2026-10-15T10:30:00"
  },
  "timestamp": "2025-10-15T10:45:00"
}
```

#### 4. 轮换密钥

**接口地址**: `POST /api/gateway/keys/{keyId}/rotation`

**说明**: 生成新密钥，旧密钥保持有效30天用于过渡

**响应示例**:
```json
{
  "status": 200,
  "message": "密钥轮换成功",
  "data": {
    "new_key_id": 1845987654321099,
    "new_api_key": "ak_nE8wK3mP5qR7sT9uV2xA4bC6dF8gH1j",
    "new_api_secret": "sk_kL3mN5oP7qR9sT1uV3wX5yZ7aB9cD1e",
    "old_key_expire_time": "2025-11-14T10:30:00"
  },
  "timestamp": "2025-10-15T10:30:00"
}
```

#### 5. 撤销密钥

**接口地址**: `DELETE /api/gateway/keys/{keyId}`

**权限要求**: `api:key:revoke`

### 权限管理接口

#### 1. 授予接口权限

**接口地址**: `POST /api/gateway/permission/grant`

**请求参数**:
```json
{
  "appId": 1845123456789012,
  "interfaceIds": [101, 102, 103],
  "expireTime": "2026-10-11T10:30:00"
}
```

#### 2. 撤销接口权限

**接口地址**: `POST /api/gateway/permission/revoke`

**请求参数**:
```json
{
  "appId": 1845123456789012,
  "interfaceIds": [101]
}
```

#### 3. 获取应用权限列表

**接口地址**: `GET /api/gateway/permission/app/{appId}`

**响应示例**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "interfaceId": 101,
      "interfacePath": "/api/user/info",
      "interfaceName": "获取用户信息",
      "httpMethod": "GET",
      "grantTime": "2025-10-11T10:30:00",
      "expireTime": "2026-10-11T10:30:00"
    }
  ]
}
```

### API 路由管理接口

#### 1. 创建路由规则

**接口地址**: `POST /api/gateway/routes`

**请求体**:
```json
{
  "routePath": "/api/users/*",
  "targetUrl": "http://backend-service:8080/users",
  "loadBalanceStrategy": "ROUND_ROBIN",
  "enableCache": true,
  "cacheExpireSeconds": 300
}
```

#### 2. 获取路由列表

**接口地址**: `GET /api/gateway/routes`

**查询参数**:
- `pageNum`: 页码（默认1）
- `pageSize`: 每页大小（默认10）
- `status`: 路由状态（可选）

### 监控统计接口

#### 1. 获取应用调用统计

**接口地址**: `GET /api/gateway/applications/{appId}/statistics`

**请求参数**:
| 参数名 | 类型 | 必填 | 说明 |
|-------|------|-----|------|
| appId | Long | 是 | 应用ID |
| days | Integer | 否 | 统计天数，默认7天 |

**响应示例**:
```json
{
  "status": 200,
  "message": "OK",
  "data": {
    "total_calls": 15234,
    "success_calls": 15000,
    "failed_calls": 234,
    "success_rate": 98.46,
    "avg_response_time": 125,
    "daily_stats": [
      {
        "date": "2025-10-15",
        "calls": 2180,
        "success": 2150,
        "failed": 30,
        "avg_time": 120
      }
    ],
    "top_interfaces": [
      {
        "path": "/api/user/info",
        "calls": 5000,
        "avg_time": 80
      }
    ]
  },
  "timestamp": "2025-10-15T10:50:00"
}
```

#### 2. 获取调用日志

**接口地址**: `GET /api/gateway/logs/calls`

**请求参数**:
| 参数名 | 类型 | 必填 | 说明 |
|-------|------|-----|------|
| appId | Long | 否 | 应用ID |
| startTime | String | 否 | 开始时间 |
| endTime | String | 否 | 结束时间 |
| status | Integer | 否 | 调用状态：1-成功, 0-失败 |
| pageNum | Integer | 否 | 页码，默认1 |
| pageSize | Integer | 否 | 每页大小，默认10 |

**响应示例**:
```json
{
  "status": 200,
  "message": "OK",
  "data": {
    "total": 1000,
    "pages": 100,
    "records": [
      {
        "id": 123456,
        "appId": 1845123456789012,
        "appName": "示例应用",
        "interfacePath": "/api/user/info",
        "httpMethod": "GET",
        "requestTime": "2025-10-15T10:30:00",
        "responseTime": 85,
        "statusCode": 200,
        "clientIp": "192.168.1.100",
        "errorMessage": null
      }
    ]
  },
  "timestamp": "2025-10-15T10:55:00"
}
```

### 仪表板接口

#### 1. 获取概览数据

**接口地址**: `GET /api/gateway/dashboard/overview`

**响应示例**:
```json
{
  "status": 200,
  "message": "OK",
  "data": {
    "totalApplications": 150,
    "activeApplications": 120,
    "totalKeys": 300,
    "todayRequests": 50000,
    "successRate": 99.5,
    "avgResponseTime": 95
  },
  "timestamp": "2025-10-15T11:00:00"
}
```

## 错误码说明

| 错误码 | 说明 | 处理建议 |
|-------|------|---------|
| 200 | 成功 | - |
| 400 | 请求参数错误 | 检查请求参数格式和必填项 |
| 401 | 未登录或token过期 | 重新登录获取token |
| 403 | 权限不足 | 检查用户权限或联系管理员 |
| 404 | 资源不存在 | 检查请求的资源ID是否正确 |
| 429 | 请求频率超限 | 降低请求频率或申请提高限额 |
| 500 | 服务器内部错误 | 稍后重试或联系技术支持 |
| 1001 | 应用不存在 | 检查应用ID是否正确 |
| 1002 | 应用已被禁用 | 联系管理员解除禁用 |
| 1003 | 应用待审核 | 等待管理员审核通过 |
| 2001 | API密钥无效 | 检查API Key是否正确 |
| 2002 | API密钥已过期 | 生成新的API密钥 |
| 2003 | 签名验证失败 | 检查签名算法和参数 |
| 2004 | 时间戳超出范围 | 确保客户端时间准确 |
| 2005 | Nonce已使用 | 每次请求使用唯一的Nonce |
| 3001 | IP不在白名单中 | 添加IP到白名单或关闭IP限制 |
| 3002 | 接口权限不足 | 申请相应接口的访问权限 |
| 3003 | 配额已用完 | 等待配额重置或申请提高配额 |

## 最佳实践

### 1. 安全建议

- **保护 API Secret**: 永远不要在客户端代码中硬编码 API Secret
- **使用 HTTPS**: 所有 API 调用都应使用 HTTPS 协议
- **设置 IP 白名单**: 对于服务端应用，建议配置 IP 白名单
- **定期轮换密钥**: 建议每3-6个月轮换一次密钥
- **监控异常调用**: 定期查看调用日志，发现异常及时处理

### 2. 性能优化

- **缓存响应**: 对于不经常变化的数据，客户端应适当缓存
- **批量请求**: 尽量使用批量接口减少请求次数
- **异步处理**: 对于耗时操作，使用异步方式处理
- **合理设置超时**: 客户端应设置合理的超时时间（建议30秒）

### 3. 错误处理

```javascript
// 错误处理示例
async function apiCall(url, options) {
  try {
    const response = await fetch(url, options);
    const data = await response.json();

    if (data.code !== 200) {
      // 根据错误码进行不同处理
      switch (data.code) {
        case 401:
          // Token过期，重新登录
          await refreshToken();
          break;
        case 429:
          // 限流，等待后重试
          await sleep(1000);
          return apiCall(url, options);
        case 500:
          // 服务器错误，记录日志
          console.error('Server error:', data.message);
          break;
      }
    }

    return data;
  } catch (error) {
    console.error('API call failed:', error);
    throw error;
  }
}
```

## SDK 使用示例

### Java SDK

```java
// 初始化客户端
ApiGatewayClient client = new ApiGatewayClient.Builder()
    .apiKey("ak_xY3kL9mN2pQ8rS5tU6vW7xA1bC2dE3f")
    .apiSecret("sk_aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5aB6cD7e")
    .baseUrl("https://api.example.com")
    .build();

// 调用API
try {
    UserInfo userInfo = client.getUserInfo(userId);
    System.out.println("User: " + userInfo.getName());
} catch (ApiException e) {
    System.err.println("API call failed: " + e.getMessage());
}
```

### Python SDK

```python
# 初始化客户端
client = ApiGatewayClient(
    api_key="ak_xY3kL9mN2pQ8rS5tU6vW7xA1bC2dE3f",
    api_secret="sk_aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5aB6cD7e",
    base_url="https://api.example.com"
)

# 调用API
try:
    user_info = client.get_user_info(user_id)
    print(f"User: {user_info['name']}")
except ApiException as e:
    print(f"API call failed: {e}")
```

### Node.js SDK

```javascript
// 初始化客户端
const client = new ApiGatewayClient({
  apiKey: 'ak_xY3kL9mN2pQ8rS5tU6vW7xA1bC2dE3f',
  apiSecret: 'sk_aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5aB6cD7e',
  baseUrl: 'https://api.example.com'
});

// 调用API
client.getUserInfo(userId)
  .then(userInfo => {
    console.log(`User: ${userInfo.name}`);
  })
  .catch(error => {
    console.error('API call failed:', error);
  });
```

---

*文档版本: v2.0.0*  
*最后更新: 2025-10-15*  
*Java 版本: 21*  
*Spring Boot 版本: 3.2.11*  
*Sa-Token 版本: 1.44.0*