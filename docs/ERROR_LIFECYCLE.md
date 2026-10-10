# Error Response Lifecycle

Mô tả cách một exception đi từ service layer đến HTTP response trả về client.

---

## 1. Các thành phần liên quan

```
Exception xảy ra trong Controller / Service (kể cả @PreAuthorize)
        ↓
GlobalExceptionHandler (@RestControllerAdvice)
        ↓
ApiResult<T> { success: false, data: null, error: ErrorDetail }
        ↓
HTTP Response (JSON)
```

Một số lỗi xảy ra **trong servlet filter**, trước khi request tới controller — filter tự ghi
response (cùng định dạng `ApiResult`) và không đi qua `GlobalExceptionHandler`. Xem mục 4.

**Custom exceptions:**

| Class | Dùng khi |
|:---|:---|
| `ResourceNotFoundException(ErrorCode, message)` | Entity không tồn tại trong DB |
| `ForbiddenException(ErrorCode, message)` | User không đủ quyền thực hiện thao tác |
| `IllegalArgumentException(message)` | Input vi phạm business rule (trùng SKU, trùng tên...) |
| `SubscriptionLimitExceededException(ErrorCode, message)` | Vượt giới hạn của gói (số store, staff, product, warehouse) |
| `RateLimitExceededException(message, retryAfter, limit, remaining)` | Hết quota rate limit ném từ service (hiện dùng cho đăng nhập sai theo tài khoản — `LoginAttemptLimiter`) |
| `InvalidTokenException(ErrorCode, message)` | Refresh token không hợp lệ / đã dùng / hết hạn |
| `PaymentAccountUnavailableException` | Chưa có tài khoản nhận tiền đang dùng khi tạo invoice |

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

Nguồn: `exception/GlobalExceptionHandler.java`.

| Exception | HTTP Status | ErrorCode | Ghi chú |
|:---|:---:|:---|:---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` | `@Valid` fail trên request body; `field` được set |
| `MethodArgumentTypeMismatchException` | 400 | `VALIDATION_ERROR` | Path/query param sai kiểu (VD chữ vào chỗ số); `field` = tên param |
| `IllegalArgumentException` | 400 | `VALIDATION_ERROR` | Business rule vi phạm (trùng username, SKU...) |
| `IllegalStateException` | 400 | `INSUFFICIENT_STOCK` | ⚠️ Mọi `IllegalStateException` đều ra mã này — chỉ ném khi thật sự là thiếu tồn kho |
| `BadCredentialsException` | 401 | `INVALID_CREDENTIALS` | Sai username/password khi login |
| `DisabledException` | 401 | `INVALID_CREDENTIALS` | User bị deactivate (`isActive = false`) |
| `InvalidTokenException` | 401 | _(từ exception)_ | `REFRESH_TOKEN_INVALID` / `REFRESH_TOKEN_EXPIRED` |
| `SubscriptionLimitExceededException` | 402 | _(từ exception)_ | Vượt giới hạn gói |
| `ForbiddenException` | 403 | _(từ exception)_ | Không đủ quyền (service layer kiểm tra) |
| `AccessDeniedException` | 403 | `FORBIDDEN` | `@PreAuthorize` trả false |
| `ResourceNotFoundException` | 404 | _(từ exception)_ | Entity không tìm thấy |
| `NoResourceFoundException` | 404 | `RESOURCE_NOT_FOUND` | URL không tồn tại (VD `/actuator/**` trên port chính) |
| `OptimisticLockingFailureException` | 409 | `CONCURRENT_MODIFICATION` | Bản ghi bị người khác sửa cùng lúc (`@Version`) |
| `RateLimitExceededException` | 429 | `RATE_LIMIT_EXCEEDED` | Kèm header `Retry-After`, `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` |
| `PaymentAccountUnavailableException` | 503 | `PAYMENT_ACCOUNT_UNAVAILABLE` | Admin chưa cấu hình tài khoản nhận tiền |
| `AsyncRequestNotUsableException` | — | — | Client đã ngắt kết nối giữa chừng: chỉ log DEBUG, không ghi body |
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

## 4. Lỗi trả trực tiếp từ filter (không qua GlobalExceptionHandler)

Các lỗi dưới đây xảy ra trong servlet filter, **trước** `DispatcherServlet` nên `@RestControllerAdvice`
không bắt được. Filter tự ghi body — **vẫn dùng đúng envelope `ApiResult`**, nên client chỉ cần xử lý
một định dạng lỗi duy nhất.

| HTTP | `error.code` | Nơi ghi response | Khi nào |
|:---:|:---|:---|:---|
| 401 | `UNAUTHORIZED` | `authenticationEntryPoint` trong `SecurityConfig` | Thiếu / sai / hết hạn JWT ở endpoint cần đăng nhập |
| 429 | `RATE_LIMIT_EXCEEDED` | `RateLimitResponseWriter` (gọi từ `RateLimitFilter`, `AuthenticatedRateLimitFilter`) | Hết quota theo IP hoặc theo user — xem [RATE_LIMITING.md](RATE_LIMITING.md) |
| 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | `IdempotencyFilter` | Request tạo đơn trùng `Idempotency-Key` khi request đầu chưa xử lý xong |

### 4a. Không có / sai JWT (401)

```
Request không có token hoặc token invalid
    ↓
BearerTokenAuthenticationFilter: không set SecurityContext
    ↓
AuthorizationFilter: endpoint cần auth (anyRequest().authenticated()) → từ chối
    ↓
ExceptionTranslationFilter → authenticationEntryPoint (SecurityConfig)
    ↓
Ghi thẳng body (không dùng sendError — sendError trả trang HTML của servlet container)
    └── { "success": false, "data": null,
          "error": { "code": "UNAUTHORIZED", "message": "Authentication required. Provide a valid Bearer token.", "field": null } }
```

### 4b. Hết quota rate limit (429)

```
RateLimitFilter (trước Spring Security, theo IP)  hoặc  AuthenticatedRateLimitFilter (sau JWT, theo user)
    ↓
RateLimitService: bucket Redis hết token
    ↓
RateLimitResponseWriter.write(...)
    ├── Status 429 + header Retry-After / RateLimit-Limit / RateLimit-Remaining / RateLimit-Reset
    └── { "success": false, "data": null,
          "error": { "code": "RATE_LIMIT_EXCEEDED", "message": "Too many requests. Please try again later.", "field": null } }
```

Riêng giới hạn đăng nhập sai theo tài khoản được kiểm tra **trong service** (`AuthService.login` →
`LoginAttemptLimiter`), nên đi qua `GlobalExceptionHandler` (bảng mục 2) — cùng status, header và
`error.code`, chỉ khác `message`.

### 4c. @PreAuthorize fail (403)

Không phải lỗi filter: URL-level chỉ yêu cầu `authenticated()`, còn kiểm tra quyền chi tiết chạy ở
`@PreAuthorize` bên trong controller → Spring ném `AccessDeniedException` → `GlobalExceptionHandler`
trả `403` với `error.code = FORBIDDEN`, message `Access denied`.

> **Hệ quả:** mọi lỗi API đều có cùng envelope `ApiResult`; client đọc `error.code` để phân loại.

---

## 5. Khi nào dùng exception nào

```
Tình huống                                    Exception cần throw
─────────────────────────────────────────────────────────────────
Entity không tìm thấy trong DB               ResourceNotFoundException(ErrorCode.X_NOT_FOUND, "...")
User không đủ quyền (business rule)          ForbiddenException(ErrorCode.FORBIDDEN, "...")
Input vi phạm rule (trùng tên, trùng mã...)  IllegalArgumentException("...")
Input thiếu / sai format                     Dùng @Valid trên DTO — không throw thủ công
Vượt giới hạn gói                            SubscriptionLimitExceededException(...)
Hết quota tự kiểm tra trong service          RateLimitExceededException(...) — quota theo IP/user ở filter thì không cần
Thiếu tồn kho                                IllegalStateException("...") — lỗi trạng thái khác KHÔNG dùng exception này
Lỗi bất ngờ (bug)                           Không catch — để catch-all handler xử lý → 500
```

> **Không nên** dùng `RuntimeException` trực tiếp — mất `ErrorCode`, response body sẽ fallback về catch-all 500.
