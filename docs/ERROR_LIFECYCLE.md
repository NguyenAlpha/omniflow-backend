# Error Response Lifecycle

Mô tả cách một exception đi từ service layer đến HTTP response trả về client.

---

## 1. Các thành phần liên quan

```
Exception xảy ra trong Service
        ↓
GlobalExceptionHandler (@RestControllerAdvice)
        ↓
ApiResult<T> { success: false, data: null, error: ErrorDetail }
        ↓
HTTP Response (JSON)
```

**Custom exceptions:**

| Class | Dùng khi |
|:---|:---|
| `ResourceNotFoundException(ErrorCode, message)` | Entity không tồn tại trong DB |
| `ForbiddenException(ErrorCode, message)` | User không đủ quyền thực hiện thao tác |
| `IllegalArgumentException(message)` | Input vi phạm business rule (trùng SKU, trùng tên...) |

**Response body chuẩn:**

```json
// Success
{ "success": true,  "data": { ... }, "error": null }

// Failure
{ "success": false, "data": null,    "error": { "code": "STORE_NOT_FOUND", "message": "Store not found", "field": null } }

// Validation failure (field không null)
{ "success": false, "data": null,    "error": { "code": "VALIDATION_ERROR", "message": "must not be blank", "field": "name" } }
```

---

## 2. Bảng mapping Exception → HTTP Response

| Exception | HTTP Status | ErrorCode | Ghi chú |
|:---|:---:|:---|:---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` | `@Valid` fail trên request body; `field` được set |
| `IllegalArgumentException` | 400 | `VALIDATION_ERROR` | Business rule vi phạm (trùng username, SKU...) |
| `BadCredentialsException` | 401 | `INVALID_CREDENTIALS` | Sai username/password khi login |
| `DisabledException` | 401 | `INVALID_CREDENTIALS` | User bị deactivate (`isActive = false`) |
| `ResourceNotFoundException` | 404 | _(từ exception)_ | Entity không tìm thấy |
| `ForbiddenException` | 403 | _(từ exception)_ | Không đủ quyền (service layer kiểm tra) |
| `Exception` (catch-all) | 500 | `INTERNAL_ERROR` | Mọi lỗi chưa được handle |

---

## 3. Luồng chi tiết theo từng loại lỗi

### 3a. Validation Error (`@Valid` fail)

```
Client gửi request body thiếu field bắt buộc
    ↓
Spring MVC deserialize body → @Valid kích hoạt
    ↓
MethodArgumentNotValidException được ném (trước khi vào Controller)
    ↓
GlobalExceptionHandler.handleValidation()
    ├── lấy FieldError đầu tiên
    ├── ErrorDetail { code: "VALIDATION_ERROR", message: fe.defaultMessage, field: "fieldName" }
    └── ResponseEntity 400
```

### 3b. Business Rule Error (`IllegalArgumentException`)

```
Service phát hiện vi phạm (VD: username trùng)
    ↓
throw new IllegalArgumentException("Username already taken")
    ↓
GlobalExceptionHandler.handleIllegalArgument()
    ├── ErrorDetail { code: "VALIDATION_ERROR", message: ex.message, field: null }
    └── ResponseEntity 400
```

### 3c. Resource Not Found

```
Service không tìm thấy entity
    ↓
throw new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found")
    ↓
GlobalExceptionHandler.handleNotFound()
    ├── ErrorDetail { code: "STORE_NOT_FOUND", message: "Store not found", field: null }
    └── ResponseEntity 404
```

### 3d. Forbidden (service layer)

```
Service kiểm tra quyền và thấy không đủ
    ↓
throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot remove the OWNER from store")
    ↓
GlobalExceptionHandler.handleForbidden()
    ├── ErrorDetail { code: "FORBIDDEN", message: "Cannot remove the OWNER from store", field: null }
    └── ResponseEntity 403
```

### 3e. Bad Credentials (login)

```
Client gửi sai password
    ↓
AuthService.login() → authenticationManager.authenticate()
    ↓
DaoAuthenticationProvider ném BadCredentialsException
    ↓
GlobalExceptionHandler.handleBadCredentials()
    ├── ErrorDetail { code: "INVALID_CREDENTIALS", message: "Invalid username or password" }
    └── ResponseEntity 401

Lưu ý: message cố định, không lộ "user không tồn tại" hay "sai password" để tránh user enumeration
```

---

## 4. Lỗi từ Spring Security — format KHÁC với ApiResult

Hai loại lỗi sau **không đi qua** `GlobalExceptionHandler` — chúng được Spring Security xử lý trực tiếp và trả về format của Spring Boot error controller, không phải `ApiResult`:

### 4a. Không có / sai JWT (401)

```
Request không có token hoặc token invalid
    ↓
JwtAuthFilter bỏ qua (không set SecurityContext)
    ↓
AuthorizationFilter: endpoint cần auth → từ chối
    ↓
ExceptionTranslationFilter → authenticationEntryPoint (SecurityConfig)
    ↓
response.sendError(401, "Unauthorized")
    ↓
Spring Boot BasicErrorController
    └── { "timestamp": ..., "status": 401, "error": "Unauthorized", "path": "..." }
```

### 4b. @PreAuthorize fail (403)

```
@PreAuthorize("@storeAccess.isMember(...)") → trả false
    ↓
Spring Security ném AccessDeniedException
    ↓
ExceptionTranslationFilter → default AccessDeniedHandler
    ↓
response.sendError(403, "Forbidden")
    ↓
Spring Boot BasicErrorController
    └── { "timestamp": ..., "status": 403, "error": "Forbidden", "path": "..." }
```

> **Hệ quả:** Client cần xử lý 2 format error khác nhau:
> - `ApiResult` format khi lỗi từ business logic
> - Spring Boot error format khi lỗi từ Security filter chain

---

## 5. Khi nào dùng exception nào

```
Tình huống                                    Exception cần throw
─────────────────────────────────────────────────────────────────
Entity không tìm thấy trong DB               ResourceNotFoundException(ErrorCode.X_NOT_FOUND, "...")
User không đủ quyền (business rule)          ForbiddenException(ErrorCode.FORBIDDEN, "...")
Input vi phạm rule (trùng tên, trùng mã...)  IllegalArgumentException("...")
Input thiếu / sai format                     Dùng @Valid trên DTO — không throw thủ công
Lỗi bất ngờ (bug)                           Không catch — để catch-all handler xử lý → 500
```

> **Không nên** dùng `RuntimeException` trực tiếp — mất `ErrorCode`, response body sẽ fallback về catch-all 500.
