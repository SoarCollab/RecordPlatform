# Monitor System API Documentation

This document provides comprehensive API documentation for the Monitor System, including REST APIs, WebSocket APIs, and authentication mechanisms.

## Table of Contents

1. [API Overview](#api-overview)
2. [Authentication](#authentication)
3. [REST API Endpoints](#rest-api-endpoints)
4. [WebSocket APIs](#websocket-apis)
5. [Data Models](#data-models)
6. [Error Handling](#error-handling)
7. [Rate Limiting](#rate-limiting)
8. [Examples](#examples)

## API Overview

The Monitor System provides multiple API interfaces:

- **REST API**: HTTP-based APIs for data operations, configuration, and management
- **WebSocket API**: Real-time communication for live metrics and SSH terminal
- **Certificate API**: X.509 certificate-based authentication for client registration

### Base URLs

| Environment | Base URL | Description |
|-------------|----------|-------------|
| Development | `http://localhost:8080` | Local development |
| Staging | `https://monitor-staging.yourdomain.com` | Staging environment |
| Production | `https://monitor.yourdomain.com` | Production environment |

### API Versioning

The API uses URL path versioning:
- `/api/v1/` - Version 1 (Legacy)
- `/api/v2/` - Version 2 (Current)

## Authentication

### JWT Authentication

Most API endpoints require JWT authentication. Include the token in the Authorization header:

```http
Authorization: Bearer <jwt-token>
```

#### Obtaining JWT Token

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "password"
}
```

**Response:**
```json
{
  "success": true,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expire": 3600,
    "role": "admin",
    "username": "admin"
  }
}
```

### Certificate Authentication

Client applications use X.509 certificates for authentication:

```http
POST /monitor/register
Authorization: Bearer <registration-token>
X-Client-Certificate: <base64-encoded-certificate>
```

### API Key Authentication

For programmatic access, use API keys:

```http
X-API-Key: <api-key>
```

## REST API Endpoints

### Authentication Endpoints

#### Request Email Verification Code

```http
GET /api/auth/ask-code?email={email}&type={type}
```

**Parameters:**
- `email` (string, required): Email address
- `type` (string, required): Code type (`register`, `reset`)

**Response:**
```json
{
  "success": true,
  "message": "验证码已发送",
  "data": null
}
```

#### Reset Password Confirmation

```http
POST /api/auth/reset-confirm
Content-Type: application/json

{
  "email": "user@example.com",
  "code": "123456"
}
```

#### Reset Password

```http
POST /api/auth/reset-password
Content-Type: application/json

{
  "email": "user@example.com",
  "code": "123456",
  "password": "newpassword"
}
```

#### Logout

```http
DELETE /api/auth/logout
Authorization: Bearer <jwt-token>
```

### Monitor Management Endpoints

#### List All Clients

```http
GET /api/monitor/list
Authorization: Bearer <jwt-token>
```

**Response:**
```json
{
  "success": true,
  "message": "获取成功",
  "data": [
    {
      "id": 1,
      "name": "Web Server 01",
      "location": "Beijing",
      "osName": "Ubuntu 20.04",
      "osVersion": "20.04.3 LTS",
      "ip": "192.168.1.100",
      "cpuName": "Intel Core i7-8700K",
      "cpuCore": 8,
      "memory": 16384,
      "disk": 512000,
      "online": true,
      "registerTime": "2024-01-15T10:30:00Z"
    }
  ]
}
```

#### Get Simple Client List

```http
GET /api/monitor/simple-list
Authorization: Bearer <jwt-token>
```

**Response:**
```json
{
  "success": true,
  "message": "获取成功",
  "data": [
    {
      "id": 1,
      "name": "Web Server 01",
      "location": "Beijing",
      "online": true
    }
  ]
}
```

#### Rename Client

```http
POST /api/monitor/rename
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "id": 1,
  "name": "New Server Name",
  "location": "Shanghai"
}
```

#### Rename Node

```http
POST /api/monitor/node
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "id": 1,
  "node": "node-name",
  "name": "New Node Name"
}
```

#### Get Client Details

```http
GET /api/monitor/details?clientId={clientId}
Authorization: Bearer <jwt-token>
```

**Response:**
```json
{
  "success": true,
  "message": "获取成功",
  "data": {
    "id": 1,
    "name": "Web Server 01",
    "location": "Beijing",
    "osName": "Ubuntu",
    "osVersion": "20.04.3 LTS",
    "ip": "192.168.1.100",
    "cpuName": "Intel Core i7-8700K",
    "cpuCore": 8,
    "memory": 16384,
    "disk": 512000,
    "online": true,
    "registerTime": "2024-01-15T10:30:00Z",
    "runtime": {
      "timestamp": "2024-10-17T10:30:00Z",
      "cpuUsage": 45.2,
      "memoryUsage": 68.5,
      "diskUsage": 78.3,
      "networkUpload": 1024,
      "networkDownload": 2048
    }
  }
}
```

#### Get Runtime History

```http
GET /api/monitor/runtime_history?clientId={clientId}
Authorization: Bearer <jwt-token>
```

**Response:**
```json
{
  "success": true,
  "message": "获取成功",
  "data": {
    "disk": [
      {"timestamp": "2024-10-17T10:00:00Z", "value": 75.2},
      {"timestamp": "2024-10-17T10:05:00Z", "value": 76.1}
    ],
    "memory": [
      {"timestamp": "2024-10-17T10:00:00Z", "value": 65.8},
      {"timestamp": "2024-10-17T10:05:00Z", "value": 68.5}
    ],
    "cpu": [
      {"timestamp": "2024-10-17T10:00:00Z", "value": 42.3},
      {"timestamp": "2024-10-17T10:05:00Z", "value": 45.2}
    ]
  }
}
```

#### Get Current Runtime

```http
GET /api/monitor/runtime_now?clientId={clientId}
Authorization: Bearer <jwt-token>
```

#### Generate Registration Token

```http
GET /api/monitor/register
Authorization: Bearer <jwt-token>
```

**Response:**
```json
{
  "success": true,
  "message": "生成成功",
  "data": "reg_token_abc123def456"
}
```

#### Delete Client

```http
GET /api/monitor/delete?clientId={clientId}
Authorization: Bearer <jwt-token>
```

#### Save SSH Connection

```http
POST /api/monitor/ssh-save
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "clientId": 1,
  "ip": "192.168.1.100",
  "port": 22,
  "username": "root",
  "password": "password"
}
```

#### Get SSH Connection

```http
GET /api/monitor/ssh?clientId={clientId}
Authorization: Bearer <jwt-token>
```

### User Management Endpoints

#### Change Password

```http
POST /api/user/change-password
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "password": "currentpassword",
  "new_password": "newpassword"
}
```

#### Modify Email

```http
POST /api/user/modify-email
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "email": "newemail@example.com",
  "code": "123456"
}
```

#### Create Sub Account

```http
POST /api/user/sub/create
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "username": "subuser",
  "email": "subuser@example.com",
  "password": "password",
  "clients": [1, 2, 3]
}
```

#### Delete Sub Account

```http
GET /api/user/sub/delete?uid={uid}
Authorization: Bearer <jwt-token>
```

#### List Sub Accounts

```http
GET /api/user/sub/list
Authorization: Bearer <jwt-token>
```

### Data Ingestion Endpoints (v2)

#### Ingest Single Metrics

```http
POST /api/v2/data/metrics
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "clientId": "client-001",
  "timestamp": "2024-10-17T10:30:00Z",
  "metrics": {
    "cpu.usage": 45.2,
    "memory.usage": 68.5,
    "disk.usage": 78.3,
    "network.upload": 1024,
    "network.download": 2048
  },
  "tags": {
    "environment": "production",
    "region": "us-east-1"
  }
}
```

#### Ingest Batch Metrics

```http
POST /api/v2/data/metrics/batch
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "metrics": [
    {
      "clientId": "client-001",
      "timestamp": "2024-10-17T10:30:00Z",
      "metrics": {
        "cpu.usage": 45.2,
        "memory.usage": 68.5
      }
    },
    {
      "clientId": "client-002",
      "timestamp": "2024-10-17T10:30:00Z",
      "metrics": {
        "cpu.usage": 52.1,
        "memory.usage": 72.3
      }
    }
  ]
}
```

#### Validate Metrics

```http
POST /api/v2/data/metrics/validate
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "clientId": "client-001",
  "timestamp": "2024-10-17T10:30:00Z",
  "metrics": {
    "cpu.usage": 45.2
  }
}
```

### Data Export Endpoints

#### Export Data Synchronously

```http
POST /api/v1/exports/sync
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "clientIds": ["client-001", "client-002"],
  "startTime": "2024-10-17T00:00:00Z",
  "endTime": "2024-10-17T23:59:59Z",
  "metrics": ["cpu.usage", "memory.usage"],
  "format": "CSV",
  "aggregation": "HOURLY"
}
```

#### Export Data Asynchronously

```http
POST /api/v1/exports/async
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "clientIds": ["client-001"],
  "startTime": "2024-10-01T00:00:00Z",
  "endTime": "2024-10-17T23:59:59Z",
  "metrics": ["cpu.usage", "memory.usage", "disk.usage"],
  "format": "JSON",
  "aggregation": "DAILY"
}
```

**Response:**
```json
{
  "success": true,
  "message": "导出任务已创建",
  "data": {
    "exportId": "export-123456",
    "status": "PROCESSING",
    "estimatedCompletionTime": "2024-10-17T10:35:00Z"
  }
}
```

#### Get Export Status

```http
GET /api/v1/exports/{exportId}/status
Authorization: Bearer <jwt-token>
```

#### Cancel Export

```http
DELETE /api/v1/exports/{exportId}
Authorization: Bearer <jwt-token>
```

#### List Exports

```http
GET /api/v1/exports?page=0&size=20&status=COMPLETED
Authorization: Bearer <jwt-token>
```

### Client Registration Endpoints

#### Register Client

```http
GET /monitor/register
Authorization: Bearer <registration-token>
```

#### Update Client Details

```http
POST /monitor/detail
Authorization: Bearer <client-token>
Content-Type: application/json

{
  "osName": "Ubuntu",
  "osVersion": "20.04.3 LTS",
  "ip": "192.168.1.100",
  "cpuName": "Intel Core i7-8700K",
  "cpuCore": 8,
  "memory": 16384,
  "disk": 512000
}
```

#### Update Runtime Details

```http
POST /monitor/runtime
Authorization: Bearer <client-token>
Content-Type: application/json

{
  "timestamp": "2024-10-17T10:30:00Z",
  "cpuUsage": 45.2,
  "memoryUsage": 68.5,
  "diskUsage": 78.3,
  "networkUpload": 1024,
  "networkDownload": 2048,
  "diskRead": 512,
  "diskWrite": 256
}
```

## WebSocket APIs

### Connection Endpoints

#### Real-time Metrics WebSocket

```
ws://monitor.yourdomain.com/ws/metrics
```

**Authentication:** Include JWT token in connection headers or query parameter:
```
ws://monitor.yourdomain.com/ws/metrics?token=<jwt-token>
```

**Message Format:**
```json
{
  "type": "METRICS_UPDATE",
  "clientId": "client-001",
  "timestamp": "2024-10-17T10:30:00Z",
  "data": {
    "cpu.usage": 45.2,
    "memory.usage": 68.5,
    "disk.usage": 78.3
  }
}
```

#### SSH Terminal WebSocket

```
ws://monitor.yourdomain.com/ws/ssh
```

**Connection Parameters:**
```json
{
  "clientId": "client-001",
  "sessionId": "ssh-session-123"
}
```

**Message Types:**

1. **Command Input:**
```json
{
  "type": "COMMAND",
  "data": "ls -la"
}
```

2. **Terminal Output:**
```json
{
  "type": "OUTPUT",
  "data": "total 24\ndrwxr-xr-x 2 root root 4096 Oct 17 10:30 .\n"
}
```

3. **Terminal Resize:**
```json
{
  "type": "RESIZE",
  "cols": 80,
  "rows": 24
}
```

4. **Session Control:**
```json
{
  "type": "CONTROL",
  "action": "CLOSE"
}
```

### WebSocket Events

#### Connection Events

1. **Connection Established:**
```json
{
  "type": "CONNECTION_ESTABLISHED",
  "sessionId": "ws-session-123",
  "timestamp": "2024-10-17T10:30:00Z"
}
```

2. **Authentication Success:**
```json
{
  "type": "AUTH_SUCCESS",
  "userId": 1,
  "role": "admin"
}
```

3. **Authentication Failed:**
```json
{
  "type": "AUTH_FAILED",
  "error": "Invalid token"
}
```

#### Metrics Events

1. **Real-time Metrics:**
```json
{
  "type": "METRICS_REALTIME",
  "clientId": "client-001",
  "timestamp": "2024-10-17T10:30:00Z",
  "metrics": {
    "cpu.usage": 45.2,
    "memory.usage": 68.5,
    "disk.usage": 78.3,
    "network.upload": 1024,
    "network.download": 2048
  }
}
```

2. **Alert Notification:**
```json
{
  "type": "ALERT",
  "alertId": "alert-123",
  "clientId": "client-001",
  "severity": "WARNING",
  "message": "CPU usage above 80%",
  "timestamp": "2024-10-17T10:30:00Z",
  "metrics": {
    "cpu.usage": 85.2
  }
}
```

3. **Client Status Change:**
```json
{
  "type": "CLIENT_STATUS",
  "clientId": "client-001",
  "status": "ONLINE",
  "timestamp": "2024-10-17T10:30:00Z"
}
```

## Data Models

### Client Model

```json
{
  "id": 1,
  "name": "Web Server 01",
  "location": "Beijing",
  "osName": "Ubuntu",
  "osVersion": "20.04.3 LTS",
  "ip": "192.168.1.100",
  "cpuName": "Intel Core i7-8700K",
  "cpuCore": 8,
  "memory": 16384,
  "disk": 512000,
  "online": true,
  "registerTime": "2024-01-15T10:30:00Z",
  "lastHeartbeat": "2024-10-17T10:30:00Z"
}
```

### Runtime Metrics Model

```json
{
  "timestamp": "2024-10-17T10:30:00Z",
  "cpuUsage": 45.2,
  "memoryUsage": 68.5,
  "diskUsage": 78.3,
  "networkUpload": 1024,
  "networkDownload": 2048,
  "diskRead": 512,
  "diskWrite": 256,
  "processes": [
    {
      "pid": 1234,
      "name": "nginx",
      "cpuUsage": 5.2,
      "memoryUsage": 128
    }
  ]
}
```

### User Model

```json
{
  "id": 1,
  "username": "admin",
  "email": "admin@example.com",
  "role": "ADMIN",
  "createTime": "2024-01-15T10:30:00Z",
  "lastLogin": "2024-10-17T10:30:00Z",
  "clients": [1, 2, 3]
}
```

### Export Request Model

```json
{
  "clientIds": ["client-001", "client-002"],
  "startTime": "2024-10-17T00:00:00Z",
  "endTime": "2024-10-17T23:59:59Z",
  "metrics": ["cpu.usage", "memory.usage"],
  "format": "CSV",
  "aggregation": "HOURLY",
  "filters": {
    "environment": "production"
  },
  "options": {
    "includeHeaders": true,
    "compression": "GZIP"
  }
}
```

### API Response Model

```json
{
  "success": true,
  "message": "操作成功",
  "data": {},
  "timestamp": "2024-10-17T10:30:00Z",
  "requestId": "req-123456"
}
```

## Error Handling

### Error Response Format

```json
{
  "success": false,
  "message": "错误描述",
  "error": {
    "code": "ERROR_CODE",
    "details": "详细错误信息",
    "timestamp": "2024-10-17T10:30:00Z",
    "requestId": "req-123456"
  }
}
```

### Common Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `AUTH_REQUIRED` | 401 | Authentication required |
| `AUTH_INVALID` | 401 | Invalid authentication credentials |
| `AUTH_EXPIRED` | 401 | Authentication token expired |
| `FORBIDDEN` | 403 | Access forbidden |
| `NOT_FOUND` | 404 | Resource not found |
| `VALIDATION_ERROR` | 400 | Request validation failed |
| `RATE_LIMIT_EXCEEDED` | 429 | Rate limit exceeded |
| `INTERNAL_ERROR` | 500 | Internal server error |
| `SERVICE_UNAVAILABLE` | 503 | Service temporarily unavailable |

### Validation Errors

```json
{
  "success": false,
  "message": "请求参数验证失败",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Validation failed",
    "fields": {
      "email": "邮箱格式不正确",
      "password": "密码长度至少8位"
    }
  }
}
```

## Rate Limiting

### Rate Limit Headers

All API responses include rate limiting headers:

```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1634472600
```

### Rate Limits by Endpoint

| Endpoint Pattern | Limit | Window |
|------------------|-------|--------|
| `/api/auth/*` | 10 requests | 1 minute |
| `/api/monitor/*` | 100 requests | 1 minute |
| `/api/v2/data/*` | 1000 requests | 1 minute |
| `/api/v1/exports/*` | 10 requests | 1 minute |

### Rate Limit Exceeded Response

```json
{
  "success": false,
  "message": "请求频率超限",
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "details": "Too many requests",
    "retryAfter": 60
  }
}
```

## Examples

### Complete Client Registration Flow

1. **Generate Registration Token (Admin)**
```bash
curl -X GET "https://monitor.yourdomain.com/api/monitor/register" \
  -H "Authorization: Bearer <admin-jwt-token>"
```

2. **Register Client**
```bash
curl -X GET "https://monitor.yourdomain.com/monitor/register" \
  -H "Authorization: Bearer <registration-token>"
```

3. **Update Client Details**
```bash
curl -X POST "https://monitor.yourdomain.com/monitor/detail" \
  -H "Authorization: Bearer <client-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "osName": "Ubuntu",
    "osVersion": "20.04.3 LTS",
    "ip": "192.168.1.100",
    "cpuName": "Intel Core i7-8700K",
    "cpuCore": 8,
    "memory": 16384,
    "disk": 512000
  }'
```

4. **Send Runtime Metrics**
```bash
curl -X POST "https://monitor.yourdomain.com/monitor/runtime" \
  -H "Authorization: Bearer <client-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "timestamp": "2024-10-17T10:30:00Z",
    "cpuUsage": 45.2,
    "memoryUsage": 68.5,
    "diskUsage": 78.3,
    "networkUpload": 1024,
    "networkDownload": 2048
  }'
```

### Data Export Example

1. **Start Async Export**
```bash
curl -X POST "https://monitor.yourdomain.com/api/v1/exports/async" \
  -H "Authorization: Bearer <jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "clientIds": ["client-001"],
    "startTime": "2024-10-01T00:00:00Z",
    "endTime": "2024-10-17T23:59:59Z",
    "metrics": ["cpu.usage", "memory.usage"],
    "format": "CSV",
    "aggregation": "HOURLY"
  }'
```

2. **Check Export Status**
```bash
curl -X GET "https://monitor.yourdomain.com/api/v1/exports/export-123456/status" \
  -H "Authorization: Bearer <jwt-token>"
```

3. **Download Export Result**
```bash
curl -X GET "https://monitor.yourdomain.com/api/v1/exports/export-123456/download" \
  -H "Authorization: Bearer <jwt-token>" \
  -o metrics-export.csv
```

### WebSocket Connection Example (JavaScript)

```javascript
// Connect to metrics WebSocket
const ws = new WebSocket('wss://monitor.yourdomain.com/ws/metrics?token=' + jwtToken);

ws.onopen = function(event) {
    console.log('WebSocket connected');
    
    // Subscribe to specific client metrics
    ws.send(JSON.stringify({
        type: 'SUBSCRIBE',
        clientIds: ['client-001', 'client-002']
    }));
};

ws.onmessage = function(event) {
    const message = JSON.parse(event.data);
    
    switch(message.type) {
        case 'METRICS_REALTIME':
            updateMetricsDisplay(message.clientId, message.metrics);
            break;
        case 'ALERT':
            showAlert(message);
            break;
        case 'CLIENT_STATUS':
            updateClientStatus(message.clientId, message.status);
            break;
    }
};

ws.onerror = function(error) {
    console.error('WebSocket error:', error);
};

ws.onclose = function(event) {
    console.log('WebSocket closed:', event.code, event.reason);
};
```

---

For more information, see:
- [Deployment Guide](DEPLOYMENT.md)
- [Configuration Guide](CONFIGURATION.md)
- [Certificate Management Guide](CERTIFICATE_MANAGEMENT.md)