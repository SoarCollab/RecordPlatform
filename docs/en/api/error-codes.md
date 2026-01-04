# Error Codes Reference

This page documents all error codes returned by the RecordPlatform API.

## Code Ranges

| Range | Category |
|-------|----------|
| 200 | Success |
| 10000-19999 | Parameter Validation Errors |
| 20000-29999 | User/Authentication Errors |
| 30000-39999 | External Service Errors (Blockchain, Storage) |
| 40000-49999 | System Errors |
| 50000-59999 | Business Data Errors |
| 60000-69999 | Messaging Service Errors |
| 70000-79999 | Permission Errors |

## Success

| Code | Name | Description |
|------|------|-------------|
| 200 | SUCCESS | Operation successful |

## Parameter Errors (10000-19999)

| Code | Name | Description |
|------|------|-------------|
| 10000 | PARAM_ERROR | Parameter error (general) |
| 10001 | PARAM_IS_INVALID | Invalid parameter |
| 10002 | PARAM_IS_BLANK | Parameter is empty |
| 10003 | PARAM_TYPE_BIND_ERROR | Parameter format error |
| 10004 | PARAM_NOT_COMPLETE | Missing parameter |
| 10005 | JSON_PARSE_ERROR | JSON parsing failed |

## User/Authentication Errors (20000-29999)

| Code | Name | Description |
|------|------|-------------|
| 20001 | USER_NOT_LOGGED_IN | User not logged in |
| 20002 | USER_LOGIN_ERROR | Account not found or wrong password |
| 20003 | USER_ACCOUNT_FORBIDDEN | Account disabled |
| 20004 | USER_NOT_EXIST | User does not exist |
| 20005 | USER_HAS_EXISTED | User already exists |
| 20006 | USER_ACCOUNT_LOCKED | Account temporarily locked (too many login failures) |

## External Service Errors (30000-39999)

| Code | Name | Description |
|------|------|-------------|
| 30001 | CONTRACT_ERROR | Smart contract call failed |
| 30002 | INVALID_RETURN_VALUE | Invalid contract return value |
| 30003 | GET_USER_FILE_ERROR | Failed to get user file |
| 30004 | DELETE_USER_FILE_ERROR | Failed to delete user file |
| 30005 | GET_USER_SHARE_FILE_ERROR | Failed to get shared file |
| 30006 | BLOCKCHAIN_ERROR | Blockchain service request failed |
| 30007 | TRANSACTION_NOT_FOUND | Transaction record not found |
| 30008 | TRANSACTION_RECEIPT_NOT_FOUND | Transaction receipt not found |
| 30009 | FILE_SERVICE_ERROR | File service request failed |
| 30010 | SERVICE_CIRCUIT_OPEN | Service temporarily unavailable (circuit breaker) |
| 30011 | SERVICE_TIMEOUT | Service response timeout |
| 30012 | STORAGE_QUORUM_NOT_REACHED | Storage write quorum not reached |
| 30013 | STORAGE_INSUFFICIENT_REPLICAS | Insufficient storage nodes available |
| 30014 | STORAGE_DEGRADED_WRITE | Storage wrote in degraded mode, will sync when nodes recover |

## System Errors (40000-49999)

| Code | Name | Description |
|------|------|-------------|
| 40001 | FILE_MAX_SIZE_OVERFLOW | Upload size exceeds limit |
| 40002 | FILE_ACCEPT_NOT_SUPPORT | Unsupported file format |
| 40003 | SYSTEM_BUSY | System busy, please retry later |
| 40004 | RATE_LIMIT_EXCEEDED | Request rate limit exceeded |
| 40005 | SERVICE_UNAVAILABLE | Service temporarily unavailable |

## Business Data Errors (50000-59999)

| Code | Name | Description |
|------|------|-------------|
| 50001 | RESULT_DATA_NONE | Data not found |
| 50002 | DATA_IS_WRONG | Data error |
| 50003 | DATA_ALREADY_EXISTED | Data already exists |
| 50004 | AUTH_CODE_ERROR | Verification code error |
| 50005 | FILE_UPLOAD_ERROR | File upload failed |
| 50006 | FILE_DOWNLOAD_ERROR | File download failed |
| 50007 | FILE_DELETE_ERROR | File delete failed |
| 50008 | FILE_NOT_EXIST | File does not exist |
| 50009 | FILE_EMPTY | File is empty |
| 50010 | FILE_RECORD_ERROR | File attestation failed |
| 50011 | SHARE_CANCELLED | Share link has been cancelled |
| 50012 | SHARE_EXPIRED | Share has expired |

## Messaging Errors (60000-69999)

| Code | Name | Description |
|------|------|-------------|
| 60001 | MESSAGE_NOT_FOUND | Message not found |
| 60002 | CONVERSATION_NOT_FOUND | Conversation not found |
| 60003 | CANNOT_MESSAGE_SELF | Cannot send message to yourself |
| 60004 | ANNOUNCEMENT_NOT_FOUND | Announcement not found |
| 60005 | TICKET_NOT_FOUND | Ticket not found |
| 60006 | TICKET_ALREADY_CLOSED | Ticket already closed |
| 60007 | TICKET_NOT_OWNER | Not authorized to operate this ticket |
| 60008 | INVALID_TICKET_STATUS | Invalid ticket status |
| 60009 | ATTACHMENT_LIMIT_EXCEEDED | Attachment count exceeds limit |

## Permission Errors (70000-79999)

| Code | Name | Description |
|------|------|-------------|
| 70001 | PERMISSION_UNAUTHENTICATED | Login required |
| 70002 | PERMISSION_UNAUTHORIZED | Insufficient permissions |
| 70004 | PERMISSION_TOKEN_EXPIRED | Token expired |
| 70005 | PERMISSION_LIMIT | Access count limited |
| 70006 | PERMISSION_TOKEN_INVALID | Invalid token |
| 70007 | PERMISSION_SIGNATURE_ERROR | Signature verification failed |

## Error Response Format

All error responses follow this structure:

```json
{
  "code": 50008,
  "message": "File does not exist",
  "data": null
}
```

## Handling Errors

### Retry-able Errors

The following errors are safe to retry with exponential backoff:

- `30010` SERVICE_CIRCUIT_OPEN
- `30011` SERVICE_TIMEOUT
- `40003` SYSTEM_BUSY
- `40004` RATE_LIMIT_EXCEEDED
- `40005` SERVICE_UNAVAILABLE

### Token Refresh

When receiving `70004` (TOKEN_EXPIRED), the client should:

1. Call refresh token endpoint (if available)
2. Or redirect to login page

### Storage Degraded Mode

`30014` (STORAGE_DEGRADED_WRITE) indicates the write succeeded but with fewer replicas than configured. The system will automatically sync when nodes recover. This is not a failure but a warning.
