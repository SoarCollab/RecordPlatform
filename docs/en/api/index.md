# API Reference

RecordPlatform REST API documentation.

## Interactive Documentation

- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8000/record-platform/v3/api-docs

Default credentials: `admin` / `123456`

## Base URL

```
http://localhost:8000/record-platform
```

## Authentication

All endpoints except `/auth/**` require JWT authentication.

### Get Token

```http
POST /auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "123456"
}
```

Response:
```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzUxMiJ9...",
    "expiresIn": 86400
  }
}
```

### Use Token

Include in all subsequent requests:
```http
Authorization: Bearer <token>
```

## API Endpoints

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/auth/login` | User login |
| POST | `/auth/register` | User registration |
| POST | `/auth/logout` | User logout |
| GET | `/auth/me` | Get current user info |

### Files

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/file/upload` | Upload file (multipart) |
| GET | `/file/list` | List user's files |
| GET | `/file/{id}` | Get file details |
| GET | `/file/download/{id}` | Download file |
| DELETE | `/file/{id}` | Delete file |
| GET | `/file/search` | Search files |

### Images

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/images/upload/avatar` | Upload user avatar |
| POST | `/images/upload/image` | Upload general image |
| GET | `/images/download/**` | Download image |

### File Sharing

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/share/create` | Create share link |
| GET | `/share/{code}` | Access shared file |
| GET | `/share/list` | List user's shares |
| DELETE | `/share/{id}` | Revoke share |
| GET | `/share/{id}/logs` | Get share access logs |

### Admin

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/admin/files` | List all files (admin) |
| GET | `/admin/files/{id}` | File detail with provenance |
| PUT | `/admin/files/{id}/status` | Update file status |
| DELETE | `/admin/files/{id}` | Force delete file |
| GET | `/admin/files/shares` | List all shares |
| DELETE | `/admin/files/shares/{code}` | Force cancel share |
| GET | `/admin/files/shares/{code}/logs` | Share access logs |
| GET | `/admin/files/shares/{code}/stats` | Share access stats |
| GET | `/admin/users` | List all users |
| PUT | `/admin/user/{id}/role` | Update user role |
| GET | `/admin/audit/logs` | Get audit logs |
| GET | `/admin/system/monitor` | System metrics |

### Tickets

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/ticket` | Create support ticket |
| GET | `/ticket/list` | List user's tickets |
| GET | `/ticket/{id}` | Get ticket details |
| POST | `/ticket/{id}/reply` | Reply to ticket |
| PUT | `/ticket/{id}/status` | Update ticket status |

### SSE (Server-Sent Events)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/sse/subscribe` | Subscribe to events |

## Response Format

All responses follow this structure:

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

### Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad Request |
| 401 | Unauthorized |
| 403 | Forbidden |
| 404 | Not Found |
| 429 | Rate Limited |
| 500 | Server Error |

### Error Response

```json
{
  "code": 400,
  "message": "Validation failed",
  "data": null
}
```

## Pagination

List endpoints support pagination:

```http
GET /file/list?page=1&size=20&sort=createdAt,desc
```

Response includes pagination info:
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

## File Upload

### Single File Upload

```http
POST /file/upload
Content-Type: multipart/form-data

file: <binary>
```

### Chunked Upload

For large files (>10MB), use chunked upload:

```http
POST /file/upload/chunk
Content-Type: multipart/form-data

chunk: <binary>
chunkNumber: 1
totalChunks: 10
fileHash: abc123...
```

After all chunks:
```http
POST /file/upload/merge
Content-Type: application/json

{
  "fileHash": "abc123...",
  "fileName": "large-file.zip",
  "totalChunks": 10
}
```

## Rate Limiting

API requests are rate-limited per user:

| Endpoint Category | Limit |
|-------------------|-------|
| Authentication | 10/min |
| File Upload | 20/min |
| File Download | 100/min |
| Other | 60/min |

Rate limit headers:
```http
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 55
X-RateLimit-Reset: 1609459200
```

## Dubbo Services

Internal services use Dubbo Triple protocol (not REST).

### BlockChainService

```java
// Store file on blockchain
SharingVO storeFile(String fileHash, String fileKey, String fileName);

// Query blockchain record
SharingVO queryFile(String txHash);

// Delete blockchain record
void deleteFile(String txHash);
```

### DistributedStorageService

```java
// Upload file chunk
void uploadChunk(String bucket, String objectKey, byte[] data);

// Download file
byte[] downloadFile(String bucket, String objectKey);

// Delete file
void deleteFile(String bucket, String objectKey);

// Check file exists
boolean exists(String bucket, String objectKey);
```

## WebSocket Events (SSE)

Subscribe to real-time events:

```javascript
const eventSource = new EventSource('/record-platform/sse/subscribe', {
  headers: { 'Authorization': 'Bearer <token>' }
});

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log(data.type, data.payload);
};
```

Event types:
- `FILE_UPLOADED` - File upload completed
- `FILE_DELETED` - File deleted
- `SHARE_ACCESSED` - Share link accessed
- `ANNOUNCEMENT` - System announcement
- `TICKET_REPLY` - Ticket reply received

