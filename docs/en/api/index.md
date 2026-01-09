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
| POST | `/api/v1/auth/login` | User login |
| POST | `/api/v1/auth/register` | User registration |
| POST | `/api/v1/auth/logout` | User logout |
| GET | `/api/v1/auth/me` | Get current user info |

#### User Registration

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "email": "user@example.com",
  "code": "123456",
  "username": "user1",
  "password": "securepass",
  "nickname": "Display Name"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| email | string | Yes | User email address |
| code | string | Yes | Email verification code |
| username | string | Yes | Login username |
| password | string | Yes | User password |
| nickname | string | No | Display name (max 50 chars) |

### Files

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/files/upload/start` | Start chunked upload |
| POST | `/api/v1/files/upload/chunk` | Upload file chunk |
| POST | `/api/v1/files/upload/complete` | Complete chunked upload |
| POST | `/api/v1/files/upload/pause` | Pause upload |
| POST | `/api/v1/files/upload/resume` | Resume upload |
| POST | `/api/v1/files/upload/cancel` | Cancel upload |
| GET | `/api/v1/files/upload/check` | Check upload status |
| GET | `/api/v1/files/upload/progress` | Get upload progress |
| GET | `/api/v1/files` | List user's files |
| GET | `/api/v1/files/{hash}` | Get file details |
| GET | `/api/v1/files/{hash}/download` | Download file |
| DELETE | `/api/v1/files/{hash}` | Delete file |
| GET | `/api/v1/files/search` | Search files |

### Images

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/images/upload/avatar` | Upload user avatar |
| POST | `/api/v1/images/upload/image` | Upload general image |
| GET | `/api/v1/images/download/**` | Download image |

### File Sharing

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/files/share` | Create share link |
| GET | `/api/v1/share/{code}/info` | Get share info (public) |
| GET | `/api/v1/files/share/{code}` | Access shared file |
| GET | `/api/v1/files/shares` | List user's shares |
| DELETE | `/api/v1/files/share/{code}` | Revoke share |
| GET | `/api/v1/files/share/{code}/logs` | Get share access logs |

#### Get Share Info (Public)

Public endpoint - no authentication required.

```http
GET /api/v1/share/{shareCode}/info
```

Returns share details including file list. Uses business codes for share status:

| Business Code | Meaning |
|---------------|---------|
| 200 | Success |
| 50009 | Share not found |
| 50010 | Share cancelled |
| 50011 | Share expired |

Response:
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

### Admin

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/files` | List all files (admin) |
| GET | `/api/v1/admin/files/{hash}` | File detail with provenance |
| PUT | `/api/v1/admin/files/{hash}/status` | Update file status |
| DELETE | `/api/v1/admin/files/{hash}` | Force delete file |
| GET | `/api/v1/admin/files/shares` | List all shares |
| DELETE | `/api/v1/admin/files/shares/{code}` | Force cancel share |
| GET | `/api/v1/admin/files/shares/{code}/logs` | Share access logs |
| GET | `/api/v1/admin/files/shares/{code}/stats` | Share access stats |
| GET | `/api/v1/system/permissions/users` | List all users |
| PUT | `/api/v1/system/permissions/users/{id}/role` | Update user role |
| GET | `/api/v1/system/audit/logs` | Get audit logs |
| GET | `/api/v1/system/monitor` | System metrics |

### Tickets

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/tickets` | Create support ticket |
| GET | `/api/v1/tickets` | List user's tickets |
| GET | `/api/v1/tickets/{id}` | Get ticket details |
| POST | `/api/v1/tickets/{id}/reply` | Reply to ticket |
| PUT | `/api/v1/tickets/{id}/status` | Update ticket status |
| GET | `/api/v1/tickets/admin` | List all tickets (admin) |
| PUT | `/api/v1/tickets/admin/{id}/assign` | Assign handler (admin) |
| PUT | `/api/v1/tickets/admin/{id}/status` | Update status (admin) |

#### Create Ticket

```http
POST /api/v1/tickets
Content-Type: application/json

{
  "title": "Unable to upload large files",
  "content": "When uploading files over 1GB, the upload fails...",
  "priority": 1,
  "category": 0,
  "attachmentIds": ["file-id-1", "file-id-2"]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| title | string | Yes | Ticket title (max 200 chars) |
| content | string | Yes | Ticket description |
| priority | int | No | 0=Low, 1=Medium (default), 2=High |
| category | int | No | See category table below (default: 99) |
| attachmentIds | string[] | No | Related file IDs |

#### Ticket Status

| Code | Status | Description |
|------|--------|-------------|
| 0 | PENDING | Awaiting processing |
| 1 | PROCESSING | In progress |
| 2 | CONFIRMING | Pending user confirmation |
| 3 | COMPLETED | Resolved |
| 4 | CLOSED | Closed |

#### Ticket Priority

| Code | Priority |
|------|----------|
| 0 | Low |
| 1 | Medium |
| 2 | High |

#### Ticket Category

| Code | Category |
|------|----------|
| 0 | Bug Report |
| 1 | Feature Request |
| 2 | Question |
| 3 | Feedback |
| 99 | Other |

#### Reply to Ticket

```http
POST /api/v1/tickets/{id}/reply
Content-Type: application/json

{
  "content": "Thank you for reporting. We are investigating...",
  "attachmentIds": []
}
```

### Friends

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/friends/requests` | Send friend request |
| GET | `/api/v1/friends/requests/received` | Get received requests |
| GET | `/api/v1/friends/requests/sent` | Get sent requests |
| POST | `/api/v1/friends/requests/{id}/accept` | Accept friend request |
| POST | `/api/v1/friends/requests/{id}/reject` | Reject friend request |
| DELETE | `/api/v1/friends/requests/{id}` | Cancel friend request |
| GET | `/api/v1/friends/requests/pending-count` | Get pending request count |
| GET | `/api/v1/friends` | Get friends list (paginated) |
| GET | `/api/v1/friends/all` | Get all friends (for selector) |
| DELETE | `/api/v1/friends/{id}` | Unfriend |
| PUT | `/api/v1/friends/{id}/remark` | Update friend remark |
| GET | `/api/v1/friends/search` | Search users |

#### Send Friend Request

```http
POST /api/v1/friends/requests
Content-Type: application/json

{
  "addresseeId": "user-external-id",
  "message": "Hi, let's be friends!"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| addresseeId | string | Yes | Target user's external ID |
| message | string | No | Request message (max 255 chars) |

#### Friend Request Status

| Code | Status | Description |
|------|--------|-------------|
| 0 | PENDING | Awaiting response |
| 1 | ACCEPTED | Request accepted |
| 2 | REJECTED | Request rejected |
| 3 | CANCELLED | Request cancelled by sender |

### Friend File Shares

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/friend-shares` | Share files to friend |
| GET | `/api/v1/friend-shares/received` | Get received shares |
| GET | `/api/v1/friend-shares/sent` | Get sent shares |
| GET | `/api/v1/friend-shares/{id}` | Get share details |
| POST | `/api/v1/friend-shares/{id}/read` | Mark share as read |
| DELETE | `/api/v1/friend-shares/{id}` | Cancel share |
| GET | `/api/v1/friend-shares/unread-count` | Get unread count |

#### Share Files to Friend

```http
POST /api/v1/friend-shares
Content-Type: application/json

{
  "friendId": "friend-external-id",
  "fileHashes": ["hash1", "hash2"],
  "message": "Check out these files!"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| friendId | string | Yes | Friend's external ID |
| fileHashes | string[] | Yes | Array of file hashes to share |
| message | string | No | Share message (max 255 chars) |

### SSE (Server-Sent Events)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/sse/connect` | Subscribe to events |
| DELETE | `/api/v1/sse/disconnect` | Disconnect SSE |
| GET | `/api/v1/sse/status` | Check connection status |

> **Note**: For comprehensive REST API documentation with all endpoints, request/response examples, and detailed field descriptions, see [API_DOCUMENTATION.md](https://github.com/SoarCollab/RecordPlatform/blob/main/API_DOCUMENTATION.md) in the project root.

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

For large files (>10MB), use chunked upload with dynamic chunk sizing:

#### Dynamic Chunk Size Strategy

The system supports dynamic chunk sizing based on file size for optimal performance:

| File Size | Recommended Chunk Size |
|-----------|------------------------|
| < 10MB    | 2MB                    |
| < 100MB   | 5MB                    |
| < 500MB   | 10MB                   |
| < 2GB     | 20MB                   |
| >= 2GB    | 50MB                   |

**Maximum chunk size limit: 80MB** (Dubbo payload limit is 100MB)

#### Start Chunked Upload

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

#### Upload Chunk

```http
POST /file/upload/chunk
Content-Type: multipart/form-data

chunk: <binary>
chunkNumber: 1
totalChunks: 10
fileHash: abc123...
```

#### Complete Upload

After all chunks are uploaded:
```http
POST /api/v1/files/upload/complete
Content-Type: application/x-www-form-urlencoded

clientId=abc123...
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
const eventSource = new EventSource('/record-platform/api/v1/sse/connect', {
  headers: { 'Authorization': 'Bearer <token>' }
});

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log(data.type, data.payload);
};
```

> **Note**: Native EventSource doesn't support custom headers. The platform uses a short-lived token passed via query parameter for authenticated SSE connections.

Event types (kebab-case format):
- `message-received` - New private message
- `announcement-published` - New system announcement
- `ticket-updated` - Ticket status update or reply
- `friend-request` - New friend request received
- `friend-accepted` - Friend request accepted
- `friend-share` - Friend shared files with you
- `heartbeat` - Connection heartbeat
- `connected` - Connection established

