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
- `GET /api/v1/auth/ask-code`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/reset-confirm`
- `POST /api/v1/auth/reset-password`
- `GET /api/v1/files/getSharingFiles`
- `GET /api/v1/files/public/download`
- `GET /api/v1/files/public/decryptInfo`
- `GET /api/v1/images/download/images/**`
- `GET /api/v1/share/**`
- `GET /api/v1/sse/connect` (still requires short-lived token)

### 3) SSE dual-token flow

- `POST /api/v1/auth/sse-token`: requires standard JWT
- `GET /api/v1/sse/connect?token=...`: public route, but short-lived one-time token is mandatory

## Endpoints by Module

### Auth (`/api/v1/auth`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/auth/ask-code` | Request email verification code |
| POST | `/api/v1/auth/register` | Register user |
| POST | `/api/v1/auth/reset-confirm` | Confirm password reset |
| POST | `/api/v1/auth/reset-password` | Execute password reset |
| POST | `/api/v1/auth/refresh` | Refresh access token |
| POST | `/api/v1/auth/sse-token` | Issue short-lived SSE token (JWT required) |

> Login is handled by Spring Security: `POST /api/v1/auth/login`

### User (`/api/v1/users`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/users/info` | Get user profile |
| PUT | `/api/v1/users/info` | Update user profile |
| POST | `/api/v1/users/modify-email` | Change email |
| POST | `/api/v1/users/change-password` | Change password |

### File Upload (`/api/v1/files/upload`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/files/upload/start` | Start chunked upload |
| POST | `/api/v1/files/upload/chunk` | Upload chunk |
| POST | `/api/v1/files/upload/complete` | Complete upload |
| POST | `/api/v1/files/upload/pause` | Pause upload |
| POST | `/api/v1/files/upload/resume` | Resume upload |
| POST | `/api/v1/files/upload/cancel` | Cancel upload |
| GET | `/api/v1/files/upload/check` | Check upload status |
| GET | `/api/v1/files/upload/progress` | Query upload progress |

### Files and Sharing (`/api/v1/files`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/files/{id}` | File detail by ID |
| GET | `/api/v1/files/byHash` | File detail by hash (`fileHash/hash`) |
| GET | `/api/v1/files/list` | User file list |
| GET | `/api/v1/files/page` | User file page |
| GET | `/api/v1/files/stats` | User file stats |
| GET | `/api/v1/files/address` | Fetch download URLs |
| GET | `/api/v1/files/getTransaction` | Query blockchain transaction |
| GET | `/api/v1/files/download` | Download file (authenticated) |
| GET | `/api/v1/files/decryptInfo` | Decrypt info (authenticated) |
| GET | `/api/v1/files/getSharingFiles` | Public share file list (public) |
| GET | `/api/v1/files/shares` | My share list |
| DELETE | `/api/v1/files/delete` | Batch delete (hash/id) |
| DELETE | `/api/v1/files/deleteById` | Delete by file ID |
| POST | `/api/v1/files/share` | Create share |
| PUT | `/api/v1/files/share` | Update share |
| DELETE | `/api/v1/files/share/{shareCode}` | Cancel share |
| POST | `/api/v1/files/saveShareFile` | Save shared files to my space |
| GET | `/api/v1/files/share/download` | Shared download (authenticated) |
| GET | `/api/v1/files/share/decryptInfo` | Shared decrypt info (authenticated) |
| GET | `/api/v1/files/share/{shareCode}/access-logs` | Share access logs (admin) |
| GET | `/api/v1/files/share/{shareCode}/stats` | Share access stats (admin) |
| GET | `/api/v1/files/{id}/provenance` | File provenance graph (admin) |
| GET | `/api/v1/files/public/download` | Public shared download (public) |
| GET | `/api/v1/files/public/decryptInfo` | Public decrypt info (public) |

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
| POST | `/api/v1/friends/requests/{requestId}/accept` | Accept request |
| POST | `/api/v1/friends/requests/{requestId}/reject` | Reject request |
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
| POST | `/api/v1/friend-shares/{shareId}/read` | Mark as read |
| DELETE | `/api/v1/friend-shares/{shareId}` | Cancel share |
| GET | `/api/v1/friend-shares/unread-count` | Unread count |

### Conversations (`/api/v1/conversations`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/conversations` | Conversation list |
| GET | `/api/v1/conversations/{id}` | Conversation detail + messages |
| GET | `/api/v1/conversations/unread-count` | Unread conversation count |
| POST | `/api/v1/conversations/{id}/read` | Mark conversation as read |
| DELETE | `/api/v1/conversations/{id}` | Delete conversation |

> `GET /api/v1/conversations/read` is a defensive fallback endpoint that intentionally returns 404.

### Messages (`/api/v1/messages`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/messages` | Send private message |
| POST | `/api/v1/messages/to/{receiverId}` | Send by receiver ID |
| GET | `/api/v1/messages/unread-count` | Total unread messages |

### Announcements (`/api/v1/announcements`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/announcements/latest` | Latest announcements |
| GET | `/api/v1/announcements` | Announcement list |
| GET | `/api/v1/announcements/{id}` | Announcement detail |
| GET | `/api/v1/announcements/unread-count` | Unread announcement count |
| POST | `/api/v1/announcements/{id}/read` | Mark one announcement as read |
| POST | `/api/v1/announcements/read-all` | Mark all announcements as read |
| GET | `/api/v1/announcements/admin/list` | Admin announcement list |
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
| GET | `/api/v1/tickets/admin/list` | Admin ticket list |
| PUT | `/api/v1/tickets/admin/{id}/assign` | Assign ticket (admin) |
| PUT | `/api/v1/tickets/admin/{id}/status` | Update status (admin) |
| GET | `/api/v1/tickets/admin/pending-count` | Admin pending ticket count |

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
| POST | `/api/v1/system/permissions/roles/{role}/grant` | Grant permission |
| DELETE | `/api/v1/system/permissions/roles/{role}/revoke` | Revoke permission |

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
| GET | `/api/v1/system/audit/logs/page` | Audit log page (GET) |
| POST | `/api/v1/system/audit/logs/page` | Audit log page (POST) |
| GET | `/api/v1/system/audit/logs/{id}` | Audit log detail |
| POST | `/api/v1/system/audit/logs/export` | Export audit logs |
| GET | `/api/v1/system/audit/high-frequency` | High-frequency operations |
| POST | `/api/v1/system/audit/sensitive/page` | Sensitive operation page |
| GET | `/api/v1/system/audit/error-stats` | Error stats |
| GET | `/api/v1/system/audit/time-distribution` | Time distribution |
| GET | `/api/v1/system/audit/configs` | Audit configs |
| PUT | `/api/v1/system/audit/configs` | Update audit configs |
| GET | `/api/v1/system/audit/check-anomalies` | Check anomalies |
| POST | `/api/v1/system/audit/backup-logs` | Backup logs |

### SSE (`/api/v1/sse`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/sse/connect` | Establish SSE connection (short-lived token) |
| DELETE | `/api/v1/sse/disconnect` | Disconnect |
| GET | `/api/v1/sse/status` | Connection status |

Recommended flow:

```text
1) POST /api/v1/auth/sse-token   (Authorization: Bearer <jwt>)
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
