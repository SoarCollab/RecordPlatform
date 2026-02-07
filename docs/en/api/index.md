# API Reference

RecordPlatform REST API index aligned with current controllers and security rules.

## Interactive Docs

- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **OpenAPI**: http://localhost:8000/record-platform/v3/api-docs

Swagger Basic auth (Knife4j): `admin` / `123456` by default (configurable).

## Base URL

```text
http://localhost:8000/record-platform
```

## Authentication and Public Rules

### 1) Standard JWT auth

Most endpoints require:

```http
Authorization: Bearer <token>
```

### 2) Explicit public endpoints (`permitAll`)

Based on `SecurityConfiguration`:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/verification-codes`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/password-resets/confirm`
- `PUT /api/v1/auth/password-resets`
- `GET /api/v1/shares/{shareCode}/files`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/chunks`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info`
- `GET /api/v1/images/download/images/**`
- `GET /api/v1/share/**`
- `GET /api/v1/sse/connect` (still requires short-lived token)

### 3) SSE dual-token flow

- `POST /api/v1/auth/tokens/sse`: requires standard JWT
- `GET /api/v1/sse/connect?token=...`: public route, but short-lived one-time token is mandatory

## Endpoints by Module

### Auth (`/api/v1/auth`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/auth/verification-codes` | Request email verification code |
| POST | `/api/v1/auth/register` | Register user |
| POST | `/api/v1/auth/password-resets/confirm` | Confirm password reset |
| PUT | `/api/v1/auth/password-resets` | Execute password reset |
| POST | `/api/v1/auth/tokens/refresh` | Refresh access token |
| POST | `/api/v1/auth/tokens/sse` | Issue short-lived SSE token (JWT required) |

> Login is handled by Spring Security: `POST /api/v1/auth/login`

### User (`/api/v1/users`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/users/info` | Get user profile |
| PUT | `/api/v1/users/info` | Update user profile |
| PUT | `/api/v1/users/email` | Change email |
| PUT | `/api/v1/users/password` | Change password |

### File Upload (`/api/v1/upload-sessions`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/upload-sessions` | Start chunked upload |
| PUT | `/api/v1/upload-sessions/{clientId}/chunks/{chunkNumber}` | Upload chunk |
| POST | `/api/v1/upload-sessions/{clientId}/complete` | Complete upload |
| POST | `/api/v1/upload-sessions/{clientId}/pause` | Pause upload |
| POST | `/api/v1/upload-sessions/{clientId}/resume` | Resume upload |
| DELETE | `/api/v1/upload-sessions/{clientId}` | Cancel upload |
| GET | `/api/v1/upload-sessions/{clientId}` | Check upload status |
| GET | `/api/v1/upload-sessions/{clientId}/progress` | Query upload progress |

### Files and Sharing (`/api/v1/files`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/files/{id}` | File detail by ID |
| GET | `/api/v1/files/hash/{fileHash}` | File detail by hash |
| GET | `/api/v1/files` | User file list |
| GET | `/api/v1/files` | User file page |
| GET | `/api/v1/files/stats` | User file stats |
| GET | `/api/v1/files/hash/{fileHash}/addresses` | Fetch download URLs |
| GET | `/api/v1/transactions/{transactionHash}` | Query blockchain transaction |
| GET | `/api/v1/files/hash/{fileHash}/chunks` | Download file (authenticated) |
| GET | `/api/v1/files/hash/{fileHash}/decrypt-info` | Decrypt info (authenticated) |
| GET | `/api/v1/shares/{shareCode}/files` | Public share file list (public) |
| GET | `/api/v1/files/shares` | My share list |
| DELETE | `/api/v1/files/delete` | Batch delete (hash/id) |
| DELETE | `/api/v1/files/deleteById` | Delete by file ID |
| POST | `/api/v1/shares` | Create share |
| PATCH | `/api/v1/shares/{shareCode}` | Update share |
| DELETE | `/api/v1/files/share/{shareCode}` | Cancel share |
| POST | `/api/v1/shares/{shareCode}/files/save` | Save shared files to my space |
| GET | `/api/v1/shares/{shareCode}/files/{fileHash}/chunks` | Shared download (authenticated) |
| GET | `/api/v1/shares/{shareCode}/files/{fileHash}/decrypt-info` | Shared decrypt info (authenticated) |
| GET | `/api/v1/files/share/{shareCode}/access-logs` | Share access logs (admin) |
| GET | `/api/v1/files/share/{shareCode}/stats` | Share access stats (admin) |
| GET | `/api/v1/files/{id}/provenance` | File provenance graph (admin) |
| GET | `/api/v1/public/shares/{shareCode}/files/{fileHash}/chunks` | Public shared download (public) |
| GET | `/api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info` | Public decrypt info (public) |

### Admin File Audit (`/api/v1/admin/files`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/admin/files` | Get all files (paged) |
| GET | `/api/v1/admin/files/{id}` | File detail with audit info |
| PUT | `/api/v1/admin/files/{id}/status` | Update file status |
| DELETE | `/api/v1/admin/files/{id}` | Force physical file deletion |
| GET | `/api/v1/admin/files/shares` | Get all shares (paged) |
| DELETE | `/api/v1/admin/files/shares/{shareCode}` | Force cancel share |
| GET | `/api/v1/admin/files/shares/{shareCode}/logs` | Share access logs |
| GET | `/api/v1/admin/files/shares/{shareCode}/stats` | Share access stats |

### Public Share Page (`/api/v1/share`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/share/{shareCode}/info` | Get share info (public) |

### Images (`/api/v1/images`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/images/upload/avatar` | Upload avatar |
| POST | `/api/v1/images/upload/image` | Upload image |
| GET | `/api/v1/images/download/images/**` | Download image (public) |

### Friends (`/api/v1/friends`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/friends/requests` | Send friend request |
| GET | `/api/v1/friends/requests/received` | Received requests |
| GET | `/api/v1/friends/requests/sent` | Sent requests |
| PUT | `/api/v1/friends/requests/{requestId}/status` | Accept request |
| PUT | `/api/v1/friends/requests/{requestId}/status` | Reject request |
| DELETE | `/api/v1/friends/requests/{requestId}` | Cancel request |
| GET | `/api/v1/friends/requests/pending-count` | Pending request count |
| GET | `/api/v1/friends` | Friend list (paged) |
| GET | `/api/v1/friends/all` | Full friend list |
| DELETE | `/api/v1/friends/{friendId}` | Remove friend |
| PUT | `/api/v1/friends/{friendId}/remark` | Update friend remark |
| GET | `/api/v1/friends/search` | Search users |

### Friend Shares (`/api/v1/friend-shares`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/friend-shares` | Share files with friend |
| GET | `/api/v1/friend-shares/received` | Received friend shares |
| GET | `/api/v1/friend-shares/sent` | Sent friend shares |
| GET | `/api/v1/friend-shares/{shareId}` | Share detail |
| PUT | `/api/v1/friend-shares/{shareId}/read-status` | Mark as read |
| DELETE | `/api/v1/friend-shares/{shareId}` | Cancel share |
| GET | `/api/v1/friend-shares/unread-count` | Unread count |

### Conversations (`/api/v1/conversations`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/conversations` | Conversation list |
| GET | `/api/v1/conversations/{id}` | Conversation detail + messages |
| GET | `/api/v1/conversations/unread-count` | Unread conversation count |
| PUT | `/api/v1/conversations/{id}/read-status` | Mark conversation as read |
| DELETE | `/api/v1/conversations/{id}` | Delete conversation |

### Messages (`/api/v1/messages`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/messages` | Send private message |
| GET | `/api/v1/messages/unread-count` | Total unread messages |

### Announcements (`/api/v1/announcements`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/announcements/latest` | Latest announcements |
| GET | `/api/v1/announcements` | Announcement list |
| GET | `/api/v1/announcements/{id}` | Announcement detail |
| GET | `/api/v1/announcements/unread-count` | Unread announcement count |
| PUT | `/api/v1/announcements/{id}/read-status` | Mark one announcement as read |
| PUT | `/api/v1/announcements/read-status` | Mark all announcements as read |
| GET | `/api/v1/admin/announcements` | Admin announcement list |
| POST | `/api/v1/announcements` | Publish announcement (admin) |
| PUT | `/api/v1/announcements/{id}` | Update announcement (admin) |
| DELETE | `/api/v1/announcements/{id}` | Delete announcement (admin) |

### Tickets (`/api/v1/tickets`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/tickets` | My ticket list |
| GET | `/api/v1/tickets/{id}` | Ticket detail |
| POST | `/api/v1/tickets` | Create ticket |
| PUT | `/api/v1/tickets/{id}` | Update ticket |
| POST | `/api/v1/tickets/{id}/reply` | Reply ticket |
| POST | `/api/v1/tickets/{id}/close` | Close ticket |
| POST | `/api/v1/tickets/{id}/confirm` | Confirm completion |
| GET | `/api/v1/tickets/pending-count` | Pending ticket count (legacy) |
| GET | `/api/v1/tickets/unread-count` | Unread ticket count |
| GET | `/api/v1/admin/tickets` | Admin ticket list |
| PUT | `/api/v1/admin/tickets/{id}/assignee` | Assign ticket (admin) |
| PUT | `/api/v1/admin/tickets/{id}/status` | Update status (admin) |
| GET | `/api/v1/admin/tickets/pending-count` | Admin pending ticket count |

### Permissions (`/api/v1/system/permissions`, admin)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/system/permissions` | Permission tree |
| GET | `/api/v1/system/permissions/list` | Permission page list |
| GET | `/api/v1/system/permissions/modules` | Module list |
| POST | `/api/v1/system/permissions` | Create permission |
| PUT | `/api/v1/system/permissions/{id}` | Update permission |
| DELETE | `/api/v1/system/permissions/{id}` | Delete permission |
| GET | `/api/v1/system/permissions/roles/{role}` | Role permissions |
| POST | `/api/v1/system/roles/{role}/permissions` | Grant permission |
| DELETE | `/api/v1/system/roles/{role}/permissions/{permissionCode}` | Revoke permission |

### System Monitoring (`/api/v1/system`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/system/stats` | System statistics |
| GET | `/api/v1/system/chain-status` | Blockchain status |
| GET | `/api/v1/system/health` | System health |
| GET | `/api/v1/system/storage-capacity` | Storage capacity aggregation (cluster/node/domain) |
| GET | `/api/v1/system/monitor` | Aggregated monitoring metrics |

### System Audit (`/api/v1/system/audit`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/system/audit/overview` | Audit overview |
| GET | `/api/v1/system/audit/logs` | Audit log page (GET) |
| POST | `/api/v1/system/audit/logs/query` | Audit log page (POST) |
| GET | `/api/v1/system/audit/logs/{id}` | Audit log detail |
| POST | `/api/v1/system/audit/logs/export` | Export audit logs |
| GET | `/api/v1/system/audit/high-frequency` | High-frequency operations |
| POST | `/api/v1/system/audit/sensitive/page` | Sensitive operation page |
| GET | `/api/v1/system/audit/error-stats` | Error stats |
| GET | `/api/v1/system/audit/time-distribution` | Time distribution |
| GET | `/api/v1/system/audit/configs` | Audit configs |
| PUT | `/api/v1/system/audit/configs` | Update audit configs |
| POST | `/api/v1/system/audit/anomalies/check` | Check anomalies |
| POST | `/api/v1/system/audit/logs/backups` | Backup logs |

### SSE (`/api/v1/sse`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/sse/connect` | Establish SSE connection (short-lived token) |
| DELETE | `/api/v1/sse/disconnect` | Disconnect |
| GET | `/api/v1/sse/status` | Connection status |

Recommended flow:

```text
1) POST /api/v1/auth/tokens/sse   (Authorization: Bearer <jwt>)
2) GET  /api/v1/sse/connect?token=<sseToken>&connectionId=<optional>
```

Typical event types:

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

## Unified Response Format

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

- For business error codes: `/docs/en/api/error-codes.md`
- For fuller module-level details: `/API_DOCUMENTATION.md`
