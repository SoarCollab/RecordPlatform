# RecordPlatform API Documentation

> Comprehensive REST API documentation for the RecordPlatform backend services.

## Table of Contents

- [Overview](#overview)
- [Authentication](#authentication)
- [Response Format](#response-format)
- [Error Codes](#error-codes)
- [API Modules](#api-modules)
  - [Auth Module](#1-auth-module)
  - [User Module](#2-user-module)
  - [File Upload Module](#3-file-upload-module)
  - [File Operations Module](#4-file-operations-module)
  - [Image Module](#5-image-module)
  - [Conversation Module](#6-conversation-module)
  - [Message Module](#7-message-module)
  - [Announcement Module](#8-announcement-module)
  - [Ticket Module](#9-ticket-module)
  - [Permission Module](#10-permission-module-admin)
  - [Audit Module](#11-audit-module-admin)
  - [SSE Module](#12-sse-module)
- [Appendix](#appendix)

---

## Overview

### Base Information

| Property     | Value                                   |
| ------------ | --------------------------------------- |
| Base URL     | `http://localhost:8000/record-platform` |
| API Version  | v1                                      |
| API Prefix   | `/api/v1/`                              |
| Content-Type | `application/json`                      |

### Service Architecture

- **User Authentication Service**: Registration, login, password management
- **File Storage Service**: File upload/download/sharing (blockchain certification + distributed storage)
- **Messaging Service**: Private messages, conversations, SSE push notifications
- **System Management Service**: Audit logs, permissions, announcements, tickets

---

## Authentication

### Authentication Method

All protected endpoints require a **Bearer Token** in the `Authorization` header.

```
Authorization: Bearer <your_jwt_token>
```

### Obtaining a Token

Call the login endpoint to obtain a JWT token:

```
POST /api/auth/login
```

### Public Endpoints (No Auth Required)

- `POST /api/auth/login` - User login
- `GET /api/auth/logout` - User logout
- `GET /api/v1/auth/ask-code` - Request verification code
- `POST /api/v1/auth/register` - User registration
- `POST /api/v1/auth/reset-confirm` - Confirm password reset
- `POST /api/v1/auth/reset-password` - Reset password
- `GET /api/v1/files/getSharingFiles` - Get shared files by code
- `POST /api/v1/files/saveShareFile` - Save shared files
- `GET /api/v1/images/download/images/**` - Download images

---

## Response Format

All API responses use a unified `Result<T>` wrapper:

```json
{
  "code": 200,
  "message": "Operation successful",
  "data": { ... }
}
```

| Field     | Type    | Description                    |
| --------- | ------- | ------------------------------ |
| `code`    | Integer | Status code (200 = success)    |
| `message` | String  | Human-readable message         |
| `data`    | T       | Response payload (can be null) |

### Success Response Example

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "username": "john",
    "role": "user",
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "expire": "2025-01-17T12:00:00.000Z"
  }
}
```

### Error Response Example

```json
{
  "code": 20002,
  "message": "账号不存在或密码错误",
  "data": null
}
```

---

## Error Codes

### General Status Codes

| Code | Name    | Description           |
| ---- | ------- | --------------------- |
| 200  | SUCCESS | Operation successful  |
| 500  | FAIL    | Internal server error |

### Parameter Errors (10000-19999)

| Code  | Name                  | Description            |
| ----- | --------------------- | ---------------------- |
| 10000 | PARAM_ERROR           | Parameter error        |
| 10001 | PARAM_IS_INVALID      | Invalid parameter      |
| 10002 | PARAM_IS_BLANK        | Parameter is blank     |
| 10003 | PARAM_TYPE_BIND_ERROR | Parameter format error |
| 10004 | PARAM_NOT_COMPLETE    | Missing parameter      |
| 10005 | JSON_PARSE_ERROR      | JSON parsing failed    |

### User/Auth Errors (20000-29999)

| Code  | Name                   | Description                                    |
| ----- | ---------------------- | ---------------------------------------------- |
| 20001 | USER_NOT_LOGGED_IN     | User not logged in                             |
| 20002 | USER_LOGIN_ERROR       | Invalid username or password                   |
| 20003 | USER_ACCOUNT_FORBIDDEN | Account disabled                               |
| 20004 | USER_NOT_EXIST         | User does not exist                            |
| 20005 | USER_HAS_EXISTED       | User already exists                            |
| 20006 | USER_ACCOUNT_LOCKED    | Account locked due to too many failed attempts |

### External Service Errors (30000-39999)

| Code  | Name                  | Description                       |
| ----- | --------------------- | --------------------------------- |
| 30001 | CONTRACT_ERROR        | Smart contract call failed        |
| 30006 | BLOCKCHAIN_ERROR      | Blockchain service request failed |
| 30007 | TRANSACTION_NOT_FOUND | Transaction record not found      |
| 30009 | FILE_SERVICE_ERROR    | File service request failed       |
| 30010 | SERVICE_CIRCUIT_OPEN  | Service temporarily unavailable   |
| 30011 | SERVICE_TIMEOUT       | Service response timeout          |

### System Errors (40000-49999)

| Code  | Name                    | Description               |
| ----- | ----------------------- | ------------------------- |
| 40001 | FILE_MAX_SIZE_OVERFLOW  | File size exceeds limit   |
| 40002 | FILE_ACCEPT_NOT_SUPPORT | File format not supported |
| 40003 | SYSTEM_BUSY             | System busy               |
| 40004 | RATE_LIMIT_EXCEEDED     | Rate limit exceeded       |

### Data Errors (50000-59999)

| Code  | Name                | Description             |
| ----- | ------------------- | ----------------------- |
| 50001 | RESULT_DATA_NONE    | Data not found          |
| 50004 | AUTH_CODE_ERROR     | Verification code error |
| 50005 | FILE_UPLOAD_ERROR   | File upload failed      |
| 50006 | FILE_DOWNLOAD_ERROR | File download failed    |
| 50008 | FILE_NOT_EXIST      | File does not exist     |
| 50009 | FILE_EMPTY          | File is empty           |

### Message Service Errors (60000-69999)

| Code  | Name                   | Description                          |
| ----- | ---------------------- | ------------------------------------ |
| 60001 | MESSAGE_NOT_FOUND      | Message not found                    |
| 60002 | CONVERSATION_NOT_FOUND | Conversation not found               |
| 60003 | CANNOT_MESSAGE_SELF    | Cannot send message to yourself      |
| 60004 | ANNOUNCEMENT_NOT_FOUND | Announcement not found               |
| 60005 | TICKET_NOT_FOUND       | Ticket not found                     |
| 60006 | TICKET_ALREADY_CLOSED  | Ticket already closed                |
| 60007 | TICKET_NOT_OWNER       | No permission to operate this ticket |

### Permission Errors (70000-79999)

| Code  | Name                       | Description             |
| ----- | -------------------------- | ----------------------- |
| 70001 | PERMISSION_UNAUTHENTICATED | Authentication required |
| 70002 | PERMISSION_UNAUTHORIZED    | Permission denied       |
| 70004 | PERMISSION_TOKEN_EXPIRED   | Token expired           |
| 70005 | PERMISSION_LIMIT           | Access limit reached    |
| 70006 | PERMISSION_TOKEN_INVALID   | Invalid token           |

---

## API Modules

---

## 1. Auth Module

Base Path: `/api/v1/auth` (and `/api/auth` for login/logout)

### 1.1 Login

Authenticate user and obtain JWT token.

```
POST /api/auth/login
```

**Authentication**: None

**Query Parameters**:

| Parameter | Type   | Required | Description |
| --------- | ------ | -------- | ----------- |
| username  | string | Yes      | Username    |
| password  | string | Yes      | Password    |

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "username": "john",
    "role": "user",
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expire": "2025-01-17T12:00:00.000Z"
  }
}
```

**Response Fields (AuthorizeVO)**:

| Field    | Type   | Description                    |
| -------- | ------ | ------------------------------ |
| username | string | Username                       |
| role     | string | User role (user/admin/monitor) |
| token    | string | JWT token                      |
| expire   | Date   | Token expiration time          |

---

### 1.2 Logout

Invalidate current JWT token.

```
GET /api/auth/logout
```

**Authentication**: Bearer Token

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null
}
```

---

### 1.3 Request Verification Code

Send verification code to email.

```
GET /api/v1/auth/ask-code
```

**Authentication**: None

**Query Parameters**:

| Parameter | Type   | Required | Description                                 |
| --------- | ------ | -------- | ------------------------------------------- |
| email     | string | Yes      | Email address (must be valid email format)  |
| type      | string | Yes      | Code type: `register`, `reset`, or `modify` |

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null
}
```

---

### 1.4 Register

Register a new user account.

```
POST /api/v1/auth/register
```

**Authentication**: None

**Request Body (EmailRegisterVO)**:

```json
{
  "email": "user@example.com",
  "code": "123456",
  "username": "john",
  "password": "mypassword123"
}
```

| Field    | Type   | Required | Validation                         |
| -------- | ------ | -------- | ---------------------------------- |
| email    | string | Yes      | Valid email format                 |
| code     | string | Yes      | Exactly 6 characters               |
| username | string | Yes      | 1-10 chars, alphanumeric + Chinese |
| password | string | Yes      | 6-20 characters                    |

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null
}
```

---

### 1.5 Confirm Password Reset

Verify email and code before resetting password.

```
POST /api/v1/auth/reset-confirm
```

**Authentication**: None

**Request Body (ConfirmResetVO)**:

```json
{
  "email": "user@example.com",
  "code": "123456"
}
```

---

### 1.6 Reset Password

Reset user password with verification code.

```
POST /api/v1/auth/reset-password
```

**Authentication**: None

**Request Body (EmailResetVO)**:

```json
{
  "email": "user@example.com",
  "code": "123456",
  "password": "newpassword123"
}
```

---

## 2. User Module

Base Path: `/api/v1/users`

### 2.1 Get User Info

Get current user's profile information.

```
GET /api/v1/users/info
```

**Authentication**: Bearer Token

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "externalId": "abc123xyz",
    "username": "john",
    "email": "user@example.com",
    "role": "user",
    "avatar": "/api/v1/images/download/images/avatar/xxx.jpg",
    "registerTime": "2025-01-01T00:00:00.000Z"
  }
}
```

**Response Fields (AccountVO)**:

| Field        | Type   | Description                    |
| ------------ | ------ | ------------------------------ |
| externalId   | string | External user ID (for API use) |
| username     | string | Username                       |
| email        | string | Email address                  |
| role         | string | User role                      |
| avatar       | string | Avatar URL                     |
| registerTime | Date   | Registration time              |

---

### 2.2 Modify Email

Change user's email address.

```
POST /api/v1/users/modify-email
```

**Authentication**: Bearer Token

**Request Body (ModifyEmailVO)**:

```json
{
  "email": "newemail@example.com",
  "code": "123456"
}
```

---

### 2.3 Change Password

Change user's password.

```
POST /api/v1/users/change-password
```

**Authentication**: Bearer Token

**Request Body (ChangePasswordVO)**:

```json
{
  "password": "oldpassword",
  "new_password": "newpassword123"
}
```

---

## 3. File Upload Module

Base Path: `/api/v1/files/upload`

This module implements chunked file upload with pause/resume support.

### Upload Flow

1. Call `/upload/start` to initialize upload session
2. Upload each chunk via `/upload/chunk`
3. Monitor progress via `/upload/progress`
4. Call `/upload/complete` when all chunks uploaded
5. Optionally use `/upload/pause` and `/upload/resume` for pause/resume

---

### 3.1 Start Upload

Initialize a new upload session.

```
POST /api/v1/files/upload/upload/start
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter   | Type   | Required | Description                                                   |
| ----------- | ------ | -------- | ------------------------------------------------------------- |
| fileName    | string | Yes      | File name                                                     |
| fileSize    | long   | Yes      | File size in bytes                                            |
| contentType | string | Yes      | MIME type                                                     |
| clientId    | string | No       | Client-provided ID (optional, auto-generated if not provided) |
| chunkSize   | int    | Yes      | Size of each chunk                                            |
| totalChunks | int    | Yes      | Total number of chunks                                        |

**Response (StartUploadVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "clientId": "abc123",
    "chunkSize": 5242880,
    "totalChunks": 10,
    "singleChunk": false,
    "processedChunks": [],
    "resumed": false
  }
}
```

---

### 3.2 Upload Chunk

Upload a single file chunk.

```
POST /api/v1/files/upload/upload/chunk
```

**Authentication**: Bearer Token

**Form Data**:

| Parameter   | Type          | Required | Description           |
| ----------- | ------------- | -------- | --------------------- |
| file        | MultipartFile | Yes      | Chunk file data       |
| clientId    | string        | Yes      | Upload session ID     |
| chunkNumber | int           | Yes      | Chunk index (0-based) |

**Response**:

```json
{
  "code": 200,
  "message": "分片上传成功",
  "data": null
}
```

---

### 3.3 Complete Upload

Finalize the upload and trigger file processing.

```
POST /api/v1/files/upload/upload/complete
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter | Type   | Required | Description       |
| --------- | ------ | -------- | ----------------- |
| clientId  | string | Yes      | Upload session ID |

---

### 3.4 Pause Upload

Pause an ongoing upload.

```
POST /api/v1/files/upload/upload/pause
```

**Query Parameters**:

| Parameter | Type   | Required | Description       |
| --------- | ------ | -------- | ----------------- |
| clientId  | string | Yes      | Upload session ID |

---

### 3.5 Resume Upload

Resume a paused upload.

```
POST /api/v1/files/upload/upload/resume
```

**Response (ResumeUploadVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "processedChunks": [0, 1, 2, 3],
    "totalChunks": 10
  }
}
```

---

### 3.6 Cancel Upload

Cancel and clean up an upload session.

```
POST /api/v1/files/upload/upload/cancel
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter | Type   | Required | Description       |
| --------- | ------ | -------- | ----------------- |
| clientId  | string | Yes      | Upload session ID |

---

### 3.7 Check Upload Status

Get current upload status.

```
GET /api/v1/files/upload/upload/check
```

**Query Parameters**:

| Parameter | Type   | Required | Description       |
| --------- | ------ | -------- | ----------------- |
| clientId  | string | Yes      | Upload session ID |

**Response (FileUploadStatusVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "fileName": "document.pdf",
    "fileSize": 52428800,
    "clientId": "abc123",
    "paused": false,
    "status": "UPLOADING",
    "progress": 40,
    "processedChunks": [0, 1, 2, 3],
    "processedChunkCount": 4,
    "totalChunks": 10
  }
}
```

**Status Values**: `UPLOADING`, `PAUSED`, `PROCESSING_COMPLETE`, `NOT_FOUND`

---

### 3.8 Get Upload Progress

Get detailed upload progress.

```
GET /api/v1/files/upload/upload/progress
```

**Response (ProgressVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "progress": 40,
    "uploadProgress": 50,
    "processProgress": 30,
    "uploadedChunkCount": 5,
    "processedChunkCount": 3,
    "totalChunks": 10,
    "clientId": "abc123"
  }
}
```

---

## 4. File Operations Module

Base Path: `/api/v1/files`

### 4.1 Get User Files (List)

Get all user files (metadata only).

```
GET /api/v1/files/list
```

**Authentication**: Bearer Token

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "id": "123",
      "fileName": "document.pdf",
      "classification": "document",
      "fileParam": "{\"type\":\"pdf\",\"size\":1024}",
      "fileHash": "abc123hash",
      "transactionHash": "0x123...",
      "status": 1,
      "createTime": "2025-01-01T00:00:00.000Z"
    }
  ]
}
```

---

### 4.2 Get User Files (Paginated)

Get user files with pagination.

```
GET /api/v1/files/page
```

**Query Parameters**:

| Parameter | Type | Required | Description                  |
| --------- | ---- | -------- | ---------------------------- |
| pageNum   | int  | No       | Page number (default: 1)     |
| pageSize  | int  | No       | Items per page (default: 10) |

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "records": [...],
    "total": 100,
    "size": 10,
    "current": 1,
    "pages": 10
  }
}
```

---

### 4.3 Delete Files

Delete files by their hash values or IDs.

```
DELETE /api/v1/files/delete
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter   | Type     | Required | Description                               |
| ----------- | -------- | -------- | ----------------------------------------- |
| identifiers | string[] | Yes      | List of file hashes or file IDs to delete |

---

### 4.4 Delete Files by ID (Admin)

Delete files by their IDs (admin only).

```
DELETE /api/v1/files/deleteById
```

**Authentication**: Bearer Token + `file:admin` permission

**Query Parameters**:

| Parameter | Type     | Required | Description                |
| --------- | -------- | -------- | -------------------------- |
| idList    | string[] | Yes      | List of file IDs to delete |

---

### 4.5 Get File Download Address

Get pre-signed download URLs for file shards.

```
GET /api/v1/files/address
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter | Type   | Required | Description |
| --------- | ------ | -------- | ----------- |
| fileHash  | string | Yes      | File hash   |

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    "https://storage.example.com/shard1?signature=...",
    "https://storage.example.com/shard2?signature=..."
  ]
}
```

---

### 4.6 Get Transaction Record

Get blockchain transaction details for a file.

```
GET /api/v1/files/getTransaction
```

**Query Parameters**:

| Parameter       | Type   | Required | Description                 |
| --------------- | ------ | -------- | --------------------------- |
| transactionHash | string | Yes      | Blockchain transaction hash |

**Response (TransactionVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "transactionHash": "0x123...",
    "blockNumber": 12345,
    "timestamp": "2025-01-01T00:00:00.000Z"
  }
}
```

---

### 4.7 Download File

Download file content (encrypted shards).

```
GET /api/v1/files/download
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter | Type   | Required | Description |
| --------- | ------ | -------- | ----------- |
| fileHash  | string | Yes      | File hash   |

**Response**: `Result<List<byte[]>>` - List of encrypted file shards

---

### 4.8 Generate Sharing Code

Generate a sharing code for files.

```
POST /api/v1/files/share
```

**Authentication**: Bearer Token

**Request Body (fileSharingVO)**:

```json
{
  "fileHash": ["hash1", "hash2"],
  "maxAccesses": 10
}
```

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": "SHARE-ABC123XYZ"
}
```

---

### 4.9 Get Shared Files

Get files by sharing code.

```
GET /api/v1/files/getSharingFiles
```

**Authentication**: None

**Query Parameters**:

| Parameter   | Type   | Required | Description  |
| ----------- | ------ | -------- | ------------ |
| sharingCode | string | Yes      | Sharing code |

---

### 4.10 Save Shared Files

Save shared files to current user's account.

```
POST /api/v1/files/saveShareFile
```

**Request Body**:

```json
{
  "sharingFileIdList": ["fileId1", "fileId2"]
}
```

---

## 5. Image Module

Base Path: `/api/v1/images`

### 5.1 Upload Avatar

Upload user avatar image.

```
POST /api/v1/images/upload/avatar
```

**Authentication**: Bearer Token

**Form Data**:

| Parameter | Type          | Required | Description              |
| --------- | ------------- | -------- | ------------------------ |
| file      | MultipartFile | Yes      | Avatar image (max 100KB) |

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": "/api/v1/images/download/images/avatar/xxx.jpg"
}
```

---

### 5.2 Upload Image

Upload a general image.

```
POST /api/v1/images/upload/image
```

**Authentication**: Bearer Token

**Form Data**:

| Parameter | Type          | Required | Description          |
| --------- | ------------- | -------- | -------------------- |
| file      | MultipartFile | Yes      | Image file (max 5MB) |

---

### 5.3 Download Image

Download an image by path.

```
GET /api/v1/images/download/images/{imagePath}
```

**Authentication**: None

**Response**: Binary image data with `Content-Type: image/jpeg`

---

## 6. Conversation Module

Base Path: `/api/v1/conversations`

### 6.1 Get Conversation List

Get user's conversation list (paginated).

```
GET /api/v1/conversations
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter | Type | Required | Description                  |
| --------- | ---- | -------- | ---------------------------- |
| pageNum   | int  | No       | Page number (default: 1)     |
| pageSize  | int  | No       | Items per page (default: 20) |

**Response (ConversationVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "records": [
      {
        "id": "conv123",
        "otherUserId": "user456",
        "otherUsername": "jane",
        "otherAvatar": "/api/v1/images/download/images/avatar/xxx.jpg",
        "lastMessageContent": "Hello!",
        "lastMessageType": "text",
        "lastMessageAt": "2025-01-01T12:00:00.000Z",
        "unreadCount": 2
      }
    ],
    "total": 10,
    "current": 1
  }
}
```

---

### 6.2 Get Conversation Detail

Get conversation details with message history.

```
GET /api/v1/conversations/{id}
```

**Authentication**: Bearer Token

**Path Parameters**:

| Parameter | Type   | Description                |
| --------- | ------ | -------------------------- |
| id        | string | Conversation ID (external) |

**Query Parameters**:

| Parameter | Type | Required | Description                      |
| --------- | ---- | -------- | -------------------------------- |
| pageNum   | int  | No       | Message page number (default: 1) |
| pageSize  | int  | No       | Messages per page (default: 50)  |

**Response (ConversationDetailVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": "conv123",
    "otherUserId": "user456",
    "otherUsername": "jane",
    "otherAvatar": "/avatar/xxx.jpg",
    "messages": [
      {
        "id": "msg123",
        "senderId": "user456",
        "senderUsername": "jane",
        "senderAvatar": "/avatar/xxx.jpg",
        "content": "Hello!",
        "contentType": "text",
        "isMine": false,
        "isRead": true,
        "createTime": "2025-01-01T12:00:00.000Z"
      }
    ],
    "hasMore": false,
    "totalMessages": 5
  }
}
```

---

### 6.3 Get Unread Count

Get number of unread conversations.

```
GET /api/v1/conversations/unread-count
```

**Response**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "count": 3
  }
}
```

---

### 6.4 Mark as Read

Mark all messages in a conversation as read.

```
POST /api/v1/conversations/{id}/read
```

---

### 6.5 Delete Conversation

Delete a conversation.

```
DELETE /api/v1/conversations/{id}
```

---

## 7. Message Module

Base Path: `/api/v1/messages`

### 7.1 Send Message

Send a private message.

```
POST /api/v1/messages
```

**Authentication**: Bearer Token

**Request Body (SendMessageVO)**:

```json
{
  "receiverId": "user456",
  "content": "Hello!",
  "contentType": "text"
}
```

| Field       | Type   | Required | Description                                           |
| ----------- | ------ | -------- | ----------------------------------------------------- |
| receiverId  | string | Yes      | Recipient's external ID                               |
| content     | string | Yes      | Message content                                       |
| contentType | string | No       | Content type: `text`, `image`, `file` (default: text) |

**Response (MessageVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": "msg123",
    "senderId": "user123",
    "content": "Hello!",
    "contentType": "text",
    "isMine": true,
    "isRead": false,
    "createTime": "2025-01-01T12:00:00.000Z"
  }
}
```

---

### 7.2 Send Message to User

Send a message directly to a user by ID.

```
POST /api/v1/messages/to/{receiverId}
```

**Query Parameters**:

| Parameter   | Type   | Required | Description                  |
| ----------- | ------ | -------- | ---------------------------- |
| content     | string | Yes      | Message content              |
| contentType | string | No       | Content type (default: text) |

---

### 7.3 Get Unread Message Count

Get total number of unread messages.

```
GET /api/v1/messages/unread-count
```

---

## 8. Announcement Module

Base Path: `/api/v1/announcements`

### 8.1 Get Announcement List (User)

Get published announcements.

```
GET /api/v1/announcements
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter | Type | Required | Description                  |
| --------- | ---- | -------- | ---------------------------- |
| pageNum   | int  | No       | Page number (default: 1)     |
| pageSize  | int  | No       | Items per page (default: 10) |

**Response (AnnouncementVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "records": [
      {
        "id": "ann123",
        "title": "System Maintenance",
        "content": "Scheduled maintenance on...",
        "priority": 1,
        "priorityDesc": "Important",
        "pinned": true,
        "publishTime": "2025-01-01T00:00:00.000Z",
        "expireTime": null,
        "status": 1,
        "statusDesc": "Published",
        "publisherId": "admin123",
        "publisherName": "Admin",
        "read": false,
        "createTime": "2025-01-01T00:00:00.000Z"
      }
    ]
  }
}
```

**Priority Values**: 0 = Normal, 1 = Important, 2 = Urgent
**Status Values**: 0 = Draft, 1 = Published

---

### 8.2 Get Announcement Detail

```
GET /api/v1/announcements/{id}
```

---

### 8.3 Get Unread Count

```
GET /api/v1/announcements/unread-count
```

---

### 8.4 Mark as Read

```
POST /api/v1/announcements/{id}/read
```

---

### 8.5 Mark All as Read

```
POST /api/v1/announcements/read-all
```

---

### 8.6 Get All Announcements (Admin)

```
GET /api/v1/announcements/admin/list
```

**Authentication**: Bearer Token + `announcement:admin` permission

---

### 8.7 Publish Announcement (Admin)

```
POST /api/v1/announcements
```

**Authentication**: Bearer Token + `announcement:admin` permission

**Request Body (AnnouncementCreateVO)**:

```json
{
  "title": "System Maintenance",
  "content": "Scheduled maintenance on...",
  "priority": 1,
  "isPinned": 1,
  "publishTime": null,
  "expireTime": null,
  "status": 1
}
```

---

### 8.8 Update Announcement (Admin)

```
PUT /api/v1/announcements/{id}
```

---

### 8.9 Delete Announcement (Admin)

```
DELETE /api/v1/announcements/{id}
```

---

## 9. Ticket Module

Base Path: `/api/v1/tickets`

### 9.1 Get My Tickets

Get current user's tickets.

```
GET /api/v1/tickets
```

**Authentication**: Bearer Token

**Query Parameters**:

| Parameter | Type   | Required | Description                  |
| --------- | ------ | -------- | ---------------------------- |
| ticketNo  | string | No       | Ticket number filter         |
| status    | int    | No       | Status filter                |
| priority  | int    | No       | Priority filter              |
| keyword   | string | No       | Search in title/content      |
| pageNum   | int    | No       | Page number (default: 1)     |
| pageSize  | int    | No       | Items per page (default: 10) |

**Response (TicketVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "records": [
      {
        "id": "ticket123",
        "ticketNo": "TK-20250101-001",
        "title": "Cannot upload files",
        "priority": 2,
        "priorityDesc": "High",
        "status": 1,
        "statusDesc": "Processing",
        "creatorId": "user123",
        "creatorName": "john",
        "assigneeId": "admin123",
        "assigneeName": "Admin",
        "replyCount": 3,
        "createTime": "2025-01-01T00:00:00.000Z",
        "updateTime": "2025-01-01T12:00:00.000Z",
        "closeTime": null
      }
    ]
  }
}
```

**Status Values**: 0 = Pending, 1 = Processing, 2 = Pending Confirm, 3 = Completed, 4 = Closed
**Priority Values**: 0 = Low, 1 = Medium, 2 = High

---

### 9.2 Get Ticket Detail

```
GET /api/v1/tickets/{id}
```

**Response (TicketDetailVO)**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": "ticket123",
    "ticketNo": "TK-20250101-001",
    "title": "Cannot upload files",
    "content": "When I try to upload...",
    "priority": 2,
    "priorityDesc": "High",
    "status": 1,
    "statusDesc": "Processing",
    "attachments": [
      {
        "id": "att123",
        "fileId": "file456",
        "fileName": "screenshot.png",
        "fileSize": 102400,
        "fileSizeReadable": "100 KB"
      }
    ],
    "replies": [
      {
        "id": "reply123",
        "replierId": "admin123",
        "replierName": "Admin",
        "replierAvatar": "/avatar/xxx.jpg",
        "content": "We are looking into this...",
        "isInternal": false,
        "isAdmin": true,
        "attachments": [],
        "createTime": "2025-01-01T14:00:00.000Z"
      }
    ]
  }
}
```

---

### 9.3 Create Ticket

```
POST /api/v1/tickets
```

**Request Body (TicketCreateVO)**:

```json
{
  "title": "Cannot upload files",
  "content": "When I try to upload files, I get an error...",
  "priority": 1,
  "attachmentIds": ["att123"]
}
```

---

### 9.4 Reply to Ticket

```
POST /api/v1/tickets/{id}/reply
```

**Request Body (TicketReplyVO)**:

```json
{
  "content": "Thank you for the response...",
  "isInternal": false,
  "attachmentIds": []
}
```

---

### 9.5 Close Ticket

```
POST /api/v1/tickets/{id}/close
```

---

### 9.6 Confirm Ticket Completion

```
POST /api/v1/tickets/{id}/confirm
```

---

### 9.7 Get Pending Count

```
GET /api/v1/tickets/pending-count
```

---

### 9.8 Get All Tickets (Admin)

```
GET /api/v1/tickets/admin/list
```

**Authentication**: Bearer Token + `ticket:admin` permission

---

### 9.9 Assign Ticket (Admin)

```
PUT /api/v1/tickets/admin/{id}/assign
```

**Query Parameters**:

| Parameter  | Type   | Required | Description            |
| ---------- | ------ | -------- | ---------------------- |
| assigneeId | string | Yes      | Assignee's external ID |

---

### 9.10 Update Ticket Status (Admin)

```
PUT /api/v1/tickets/admin/{id}/status
```

**Query Parameters**:

| Parameter | Type | Required | Description      |
| --------- | ---- | -------- | ---------------- |
| status    | int  | Yes      | New status (0-4) |

---

### 9.11 Get Admin Pending Count

```
GET /api/v1/tickets/admin/pending-count
```

---

## 10. Permission Module (Admin)

Base Path: `/api/v1/system/permissions`

**Authentication**: Bearer Token + `system:admin` permission required for all endpoints

### 10.1 List Permissions

```
GET /api/v1/system/permissions/list
```

**Query Parameters**:

| Parameter | Type   | Required | Description                  |
| --------- | ------ | -------- | ---------------------------- |
| module    | string | No       | Filter by module name        |
| pageNum   | int    | No       | Page number (default: 1)     |
| pageSize  | int    | No       | Items per page (default: 20) |

---

### 10.2 List Modules

```
GET /api/v1/system/permissions/modules
```

---

### 10.3 Create Permission

```
POST /api/v1/system/permissions
```

**Request Body**:

```json
{
  "code": "file:delete",
  "name": "Delete File",
  "module": "file",
  "action": "delete",
  "description": "Permission to delete files"
}
```

---

### 10.4 Update Permission

```
PUT /api/v1/system/permissions/{id}
```

---

### 10.5 Delete Permission

```
DELETE /api/v1/system/permissions/{id}
```

---

### 10.6 Get Role Permissions

```
GET /api/v1/system/permissions/roles/{role}
```

**Path Parameters**:

| Parameter | Type   | Description                           |
| --------- | ------ | ------------------------------------- |
| role      | string | Role name: `user`, `admin`, `monitor` |

---

### 10.7 Grant Permission to Role

```
POST /api/v1/system/permissions/roles/{role}/grant
```

**Request Body**:

```json
{
  "permissionCode": "file:delete"
}
```

---

### 10.8 Revoke Permission from Role

```
DELETE /api/v1/system/permissions/roles/{role}/revoke
```

**Query Parameters**:

| Parameter      | Type   | Required | Description               |
| -------------- | ------ | -------- | ------------------------- |
| permissionCode | string | Yes      | Permission code to revoke |

---

## 11. Audit Module (Admin)

Base Path: `/api/v1/system/audit`

**Authentication**: Bearer Token + Admin or Monitor role required

### 11.1 Get Audit Overview

```
GET /api/v1/system/audit/overview
```

---

### 11.2 Query Operation Logs

```
GET /api/v1/system/audit/logs/page
```

**Request Body (AuditLogQueryVO)**:

```json
{
  "pageNum": 1,
  "pageSize": 10,
  "userId": "user123",
  "username": "john",
  "module": "文件操作",
  "operationType": "删除",
  "status": 0,
  "requestIp": "192.168.1.1",
  "startTime": "2025-01-01 00:00:00",
  "endTime": "2025-01-31 23:59:59"
}
```

---

### 11.3 Get Log Detail

```
GET /api/v1/system/audit/logs/{id}
```

---

### 11.4 Export Logs

```
POST /api/v1/system/audit/logs/export
```

**Response**: Excel file download

---

### 11.5 Get High Frequency Operations

```
GET /api/v1/system/audit/high-frequency
```

---

### 11.6 Get Sensitive Operations

```
POST /api/v1/system/audit/sensitive/page
```

---

### 11.7 Get Error Statistics

```
GET /api/v1/system/audit/error-stats
```

---

### 11.8 Get Time Distribution

```
GET /api/v1/system/audit/time-distribution
```

---

### 11.9 Get Audit Configs

```
GET /api/v1/system/audit/configs
```

---

### 11.10 Update Audit Config

```
PUT /api/v1/system/audit/configs
```

---

### 11.11 Check Anomalies

```
GET /api/v1/system/audit/check-anomalies
```

---

### 11.12 Backup Logs

```
POST /api/v1/system/audit/backup-logs
```

**Query Parameters**:

| Parameter         | Type    | Required | Description                                |
| ----------------- | ------- | -------- | ------------------------------------------ |
| days              | int     | No       | Days before today to backup (default: 180) |
| deleteAfterBackup | boolean | No       | Delete after backup (default: false)       |

---

## 12. SSE Module

Base Path: `/api/v1/sse`

Server-Sent Events for real-time push notifications.

### 12.1 Connect

Establish SSE connection for real-time updates.

```
GET /api/v1/sse/connect
```

**Authentication**: Bearer Token

**Response**: `text/event-stream`

**Event Types**:

- `message` - New private message
- `announcement` - New announcement
- `ticket` - Ticket update

---

### 12.2 Disconnect

Close SSE connection.

```
DELETE /api/v1/sse/disconnect
```

---

### 12.3 Get Status

Check connection status.

```
GET /api/v1/sse/status
```

**Response**:

```json
{
  "connected": true,
  "onlineCount": 15
}
```

---

## Appendix

### A. Pagination

Standard pagination parameters:

| Parameter | Type | Default | Description             |
| --------- | ---- | ------- | ----------------------- |
| pageNum   | int  | 1       | Page number (1-indexed) |
| pageSize  | int  | 10-20   | Items per page          |

Paginated response structure:

```json
{
  "records": [...],
  "total": 100,
  "size": 10,
  "current": 1,
  "pages": 10
}
```

### B. ID Obfuscation

The API uses external IDs for security:

- **Internal IDs**: Long integers used in database
- **External IDs**: String identifiers used in API requests/responses

All `id` fields in responses are external IDs. Use these when making subsequent requests.

### C. User Roles

| Role    | Description                      |
| ------- | -------------------------------- |
| user    | Standard user                    |
| admin   | Administrator (full access)      |
| monitor | Monitor (read-only admin access) |

### D. File Status

| Value | Status     | Description              |
| ----- | ---------- | ------------------------ |
| 0     | Uploading  | File is being uploaded   |
| 1     | Processing | File is being processed  |
| 2     | Completed  | File ready for use       |
| 3     | Failed     | Upload/processing failed |

### E. Ticket Priority

| Value | Priority | Description     |
| ----- | -------- | --------------- |
| 0     | Low      | Low priority    |
| 1     | Medium   | Normal priority |
| 2     | High     | Urgent          |

### F. Ticket Status

| Value | Status          | Description                |
| ----- | --------------- | -------------------------- |
| 0     | Pending         | Awaiting assignment        |
| 1     | Processing      | Being handled              |
| 2     | Pending Confirm | Awaiting user confirmation |
| 3     | Completed       | Resolved                   |
| 4     | Closed          | Closed by user or admin    |

### G. Announcement Priority

| Value | Priority  | Description              |
| ----- | --------- | ------------------------ |
| 0     | Normal    | Regular announcement     |
| 1     | Important | Highlighted announcement |
| 2     | Urgent    | Critical announcement    |

### H. Announcement Status

| Value | Status    | Description      |
| ----- | --------- | ---------------- |
| 0     | Draft     | Not published    |
| 1     | Published | Visible to users |

---

## Changelog

- **v1.0** - Initial API documentation
