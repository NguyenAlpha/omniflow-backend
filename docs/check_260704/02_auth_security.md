# Đánh giá Auth & Authorization — 2026-07-05

> **Cập nhật 2026-07-09:** Toàn bộ 17 vấn đề đã được xử lý qua các commit `2d2a8d8` (token lifecycle), `f1f7e23` (cache eviction), `459380c` (rate limit), `04a09d4` (user validation/soft-delete), `606df9f` (jwt.secret validation), `0631a54` (nhóm LOW) — mỗi mục bên dưới có dòng **Trạng thái**. Lưu ý: `application.properties` nằm trong `.gitignore` (untracked) nên tiền đề "secret commit trong repo" của mục MEDIUM secrets không đúng — xem chi tiết tại mục đó.

## 1. Phạm vi đã kiểm tra (liệt kê file)

> Lưu ý: package thực tế là `com.quiktech.pos` (không phải `com.quiktech.api`).

- `api/src/main/java/com/quiktech/backend/controller/AuthController.java`
- `api/src/main/java/com/quiktech/backend/service/AuthService.java`
- `api/src/main/java/com/quiktech/backend/security/JwtService.java`
- `api/src/main/java/com/quiktech/backend/service/RefreshTokenService.java`
- `api/src/main/java/com/quiktech/backend/config/SecurityConfig.java`
- `api/src/main/java/com/quiktech/backend/security/StoreAccessEvaluator.java`
- `api/src/main/java/com/quiktech/backend/security/BusinessAccessEvaluator.java`
- `api/src/main/java/com/quiktech/backend/security/UserPrincipal.java`
- `api/src/main/java/com/quiktech/backend/security/UserPrincipalConverter.java`
- `api/src/main/java/com/quiktech/backend/config/RateLimiterConfig.java`
- `api/src/main/java/com/quiktech/backend/filter/RateLimitFilter.java`
- `api/src/main/java/com/quiktech/backend/controller/UserController.java`
- `api/src/main/java/com/quiktech/backend/controller/AdminUserController.java`
- `api/src/main/java/com/quiktech/backend/service/UserService.java`
- `api/src/main/java/com/quiktech/backend/service/StoreService.java` (phần member/evict cache)
- `api/src/main/java/com/quiktech/backend/config/ApplicationConfig.java`
- `api/src/main/java/com/quiktech/backend/entity/{User, RefreshToken, UserRole, Role}.java`
- `api/src/main/java/com/quiktech/backend/entity/enums/RoleName.java`
- `api/src/main/java/com/quiktech/backend/repository/RefreshTokenRepository.java`
- `api/src/main/java/com/quiktech/backend/dto/request/auth/{LoginRequest, RegisterRequest, RefreshTokenRequest}.java`
- `api/src/main/java/com/quiktech/backend/dto/request/user/{ChangePasswordRequest, UpdateProfileRequest}.java`
- `api/src/main/resources/application.properties`
- `api/docs/SECURITY.md`, `api/docs/TOKEN_LIFECYCLE.md`

## 2. Tổng quan nhận xét

Kiến trúc tổng thể tốt: JWT stateless (HS256) cho access token, refresh token opaque lưu DB có rotation + reuse detection, phân quyền 3 tầng (global qua JWT claims, business/store qua evaluator + Redis cache có graceful degradation). Tài liệu SECURITY.md / TOKEN_LIFECYCLE.md chi tiết và phần lớn khớp với code.

Tuy nhiên có **2 lỗi nghiêm trọng về vòng đời token**: (1) cơ chế reuse-detection của refresh token **bị rollback bởi transaction nên vô hiệu hoàn toàn**, và (2) luồng refresh **không kiểm tra trạng thái user và không thu hồi refresh token khi deactivate/delete/đổi mật khẩu** — user bị khóa vẫn có thể duy trì phiên truy cập vô thời hạn. Ngoài ra cache phân quyền business không bao giờ được evict (trái với tài liệu), rate limit có thể bypass, và validation password có thể gây lỗi 500 runtime với BCrypt.

## 3. Vấn đề phát hiện

### [CRITICAL] Reuse-detection của refresh token bị transaction rollback — vô hiệu hoàn toàn
- Vị trí: `RefreshTokenService.java:41-59` (kết hợp `AuthService.java:84-89`)
- Mô tả: Trong `rotate()`, khi phát hiện token đã bị revoke (nghi ngờ bị đánh cắp), code gọi `refreshTokenRepository.revokeAllByUserId(...)` rồi **ném `InvalidTokenException`** (extends `RuntimeException`). Cả `rotate()` lẫn caller `AuthService.refresh()` đều `@Transactional` (cùng transaction, propagation REQUIRED) — RuntimeException ném ra làm **toàn bộ transaction rollback, bao gồm cả UPDATE revoke-all**. Tương tự, nhánh token hết hạn (`RefreshTokenService.java:54-59`) set `revokedAt` rồi ném exception — write này cũng bị rollback.
- Tác động: Tính năng "vô hiệu hóa toàn bộ token family khi phát hiện reuse" mô tả trong TOKEN_LIFECYCLE.md mục 5 **không bao giờ có hiệu lực trên DB**. Kẻ đánh cắp refresh token vẫn giữ được token hợp lệ của mình; nạn nhân dùng lại token cũ chỉ nhận 401 nhưng không có gì bị thu hồi.
- Đề xuất: Tách thao tác revoke sang transaction riêng (`@Transactional(propagation = Propagation.REQUIRES_NEW)` trên một method của service khác/tự-inject), hoặc khai báo `@Transactional(noRollbackFor = InvalidTokenException.class)` trên cả `rotate()` và `AuthService.refresh()`. Viết test: gọi refresh với token đã revoke → assert các token khác của user thực sự có `revokedAt != null` sau khi exception ném ra.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`2d2a8d8`) — `@Transactional(noRollbackFor = InvalidTokenException.class)` trên cả `RefreshTokenService.rotate()` lẫn `AuthService.refresh()` (bắt buộc cả hai vì cùng transaction REQUIRED), kèm Javadoc giải thích. ⚠️ Integration test đề xuất chưa viết.

### [CRITICAL] Refresh không kiểm tra trạng thái user; deactivate/delete không thu hồi refresh token → user bị khóa vẫn truy cập vô thời hạn
- Vị trí: `AuthService.java:84-89` (refresh), `UserService.java:83-89` (`setUserStatus`), `UserService.java:91-101` (`deleteUser`)
- Mô tả: `AuthService.refresh()` chỉ `userRepository.findById(...).orElseThrow()` — **không kiểm tra `isActive` / `deletedAt`**. Đồng thời `setUserStatus(isActive=false)` và `deleteUser()` **không gọi `refreshTokenService.revokeAll(userId)`**. Kiểm tra `isEnabled()` chỉ chạy ở `DaoAuthenticationProvider` khi login lại.
- Tác động: TOKEN_LIFECYCLE.md mục 7a nói user bị deactivate chỉ còn truy cập "tối đa 24h" — **sai**. Thực tế user bị khóa/xóa cứ 24h lại refresh: nhận access token mới + refresh token mới (+30 ngày) — truy cập **vĩnh viễn**. Đây là lỗ hổng offboarding nghiêm trọng cho hệ thống B2B (nhân viên nghỉ việc, tài khoản bị khóa vẫn vào được POS).
- Đề xuất: (1) Trong `refresh()`, kiểm tra `user.isEnabled()` (hoặc `isActive`/`deletedAt`), nếu fail → revoke all + ném 401. (2) Trong `setUserStatus` (khi chuyển sang inactive) và `deleteUser`, gọi `refreshTokenService.revokeAll(userId)`.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`2d2a8d8`) — `refresh()` check `user == null || !user.isEnabled()` (user soft-delete trả null nhờ `@SQLRestriction`) → `revokeAll` + 401; `setUserStatus` (khi deactivate) và `deleteUser` đều gọi `refreshTokenService.revokeAll(userId)`. TOKEN_LIFECYCLE.md mục 7a cập nhật lại cho đúng hành vi mới.

### [HIGH] Đổi mật khẩu không thu hồi refresh token
- Vị trí: `UserService.java:49-58` (`changePassword`)
- Mô tả: Sau khi đổi mật khẩu thành công, mọi refresh token hiện có vẫn hợp lệ. Kịch bản chuẩn "tài khoản bị lộ → đổi mật khẩu để đá kẻ xâm nhập ra" không có tác dụng — kẻ xâm nhập giữ refresh token vẫn duy trì phiên 30 ngày và tự gia hạn tiếp.
- Tác động: Vô hiệu hóa biện pháp khắc phục cơ bản nhất khi lộ tài khoản.
- Đề xuất: Gọi `refreshTokenService.revokeAll(currentUser.userId())` trong `changePassword` (client hiện tại login lại hoặc cấp bundle mới ngay trong response).
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`2d2a8d8`) — `changePassword` gọi `revokeAll` sau khi lưu password mới; docs/api/USER.md ghi chú rõ "toàn bộ refresh token bị thu hồi sau đổi mật khẩu".

### [HIGH] Username không cấm định dạng email → xung đột với `findByUsernameOrEmail`, có thể gây lỗi login / chiếm định danh
- Vị trí: `RegisterRequest.java:9` (không có `@Pattern` cho username), `UpdateProfileRequest.java:8`, `ApplicationConfig.java:110` và `AuthService.java:77` (query `findByUsernameOrEmail(x, x)`)
- Mô tả: Username chỉ có `@NotBlank @Size(max=50)` — cho phép đăng ký username là một địa chỉ email, ví dụ đúng bằng **email của user khác** (unique constraint là 2 index riêng cho `username` và `email` nên không chặn cross-field). Khi nạn nhân login bằng email, query `username = ? OR email = ?` trả về **2 rows** → `Optional` ném `IncorrectResultSizeDataAccessException` → login hỏng (DoS định danh), hoặc tệ hơn tùy hành vi là xác thực nhầm bản ghi.
- Tác động: Kẻ xấu biết email nạn nhân có thể chặn nạn nhân đăng nhập; dữ liệu định danh không nhất quán.
- Đề xuất: Thêm `@Pattern(regexp = "^[a-zA-Z0-9._-]+$")` (không cho `@`) vào `username` của cả `RegisterRequest` và `UpdateProfileRequest`; cân nhắc thêm check "username không trùng email của user khác" ở service.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`04a09d4`) — `@Pattern(regexp = "^[a-zA-Z0-9._-]+$")` thêm vào cả `RegisterRequest` và `UpdateProfileRequest`; docs/api/AUTH.md + USER.md cập nhật ràng buộc. Check cross-field ở service không thêm — pattern đã chặn `@` nên username không thể có dạng email.

### [HIGH] Cache phân quyền business không bao giờ được evict — trái với tài liệu
- Vị trí: `BusinessAccessEvaluator.java:80-98` (`evictBusinessRoleCache`, `evictBusinessMemberCache` — chỉ có định nghĩa, **không nơi nào gọi**); `StoreService.java:162,186,211` chỉ gọi `evictStoreRoleCache`
- Mô tả: SECURITY.md mục 5 yêu cầu gọi `evictBusinessMemberCache` sau khi add/update/remove store member và `evictBusinessRoleCache` sau khi thay đổi OWNER role. Grep toàn codebase: cả 2 method **không có caller nào**. `StoreService.removeMember/updateMember` chỉ evict `store:role:*`, còn `business:member:{userId}:{businessId}` vẫn sống đến hết TTL.
- Tác động: MANAGER/STAFF vừa bị gỡ khỏi store (hoặc bị hạ role) vẫn đọc/ghi **catalog của cả business** (product, price, customer, supplier) tới 300 giây. Với thao tác "đuổi nhân viên ngay lập tức", cửa sổ 5 phút là đáng kể.
- Đề xuất: Gọi `businessAccessEvaluator.evictBusinessMemberCache(userId, store.getBusiness().getId())` tại 3 điểm trong `StoreService` (addMember/updateMember/removeMember); gọi `evictBusinessRoleCache` ở nơi thay đổi OWNER (BusinessService). Bổ sung test.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`f1f7e23` + `04a09d4`) — `StoreService.addMember/updateMember/removeMember` evict cả `store:role` lẫn `business:member` cache (after-commit). Riêng `evictBusinessRoleCache`: rà soát toàn codebase **không tồn tại luồng thu hồi OWNER** (BusinessService chỉ grant khi tạo business, cache là positive-only nên grant không cần evict) — được nối vào `deleteUser` (`04a09d4`, nơi duy nhất role OWNER bị revoke); SECURITY.md mục 5/6 ghi rõ hiện trạng này. ⚠️ Test chưa bổ sung.

### [HIGH] Race condition khi refresh đồng thời — không có lock trên refresh token
- Vị trí: `RefreshTokenService.java:42-75`
- Mô tả: `rotate()` đọc token (`findByToken`), kiểm tra `revokedAt == null` rồi mới update — không có `SELECT ... FOR UPDATE` hay atomic UPDATE. Hai request refresh song song cùng 1 token đều pass check → cả hai đều được cấp refresh token mới (2 token sống song song từ 1 token gốc). Kết hợp với lỗi CRITICAL #1 (reuse detection bị rollback), tình huống này hoàn toàn không được phát hiện.
- Tác động: Rotation không đảm bảo "1 token cũ → đúng 1 token mới"; kẻ đánh cắp và nạn nhân có thể cùng rotate thành công mà không kích hoạt theft-detection.
- Đề xuất: Dùng `@Lock(LockModeType.PESSIMISTIC_WRITE)` trên `findByToken`, hoặc atomic `UPDATE refresh_tokens SET revoked_at = now WHERE token = ? AND revoked_at IS NULL` và kiểm tra affected rows == 1 trước khi cấp token mới.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`2d2a8d8`) — chọn phương án atomic UPDATE: `RefreshTokenRepository.revokeIfActive(token, now)` với điều kiện `revoked_at IS NULL` ngay trong UPDATE; `rotate()` chỉ cấp token mới khi affected rows == 1, request thua race nhận 0 row và rơi vào nhánh reuse-detection.

### [MEDIUM] Password dài quá 72 bytes gây lỗi 500 runtime với BCrypt (Spring Security 6.3+)
- Vị trí: `RegisterRequest.java:11` (`@Size(min=6, max=100)`), `ChangePasswordRequest.java:8` (không có max)
- Mô tả: Spring Boot 3.5 dùng Spring Security 6.5 — `BCryptPasswordEncoder.encode()` **ném `IllegalArgumentException` khi password > 72 bytes** (không còn âm thầm truncate). DTO cho phép tới 100 ký tự (register) và không giới hạn (change password) → request hợp lệ theo validation nhưng nổ 500 ở service.
- Tác động: Lỗi runtime khó hiểu cho client; thông báo lỗi không thân thiện.
- Đề xuất: Đặt `@Size(min=6, max=72)` cho cả 2 DTO (lưu ý ký tự UTF-8 nhiều byte — có thể giới hạn 64 cho an toàn).
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`04a09d4`) — `@Size(min = 6, max = 72)` cho `RegisterRequest.password` và `ChangePasswordRequest.newPassword`; docs/api/AUTH.md + USER.md cập nhật ràng buộc 6–72.

### [MEDIUM] Rate limit có thể bypass: tin `X-Forwarded-For` mù quáng + so khớp URI thô chưa decode + thiếu endpoint `/refresh`
- Vị trí: `RateLimitFilter.java:109-115` (extractIp), `RateLimitFilter.java:81-87` (endsWith), `RateLimitFilter.java` (không limit `/api/auth/refresh`)
- Mô tả: (1) `extractIp` lấy phần tử đầu của `X-Forwarded-For` do client tự gửi — nếu app không đứng sau reverse proxy tin cậy (hoặc proxy không override header), kẻ tấn công đổi header mỗi request để nhận bucket mới → brute-force login không giới hạn. (2) `request.getRequestURI()` là URI **chưa decode**: request tới `/api/auth/logi%6E` vẫn được Spring route vào `/api/auth/login` nhưng `endsWith("/api/auth/login")` không khớp → bypass hoàn toàn. (3) `/api/auth/refresh` là endpoint public chạm DB mỗi lần gọi nhưng không được rate limit.
- Tác động: Chống brute-force mật khẩu gần như vô hiệu với attacker chủ đích; endpoint refresh có thể bị spam dò token / gây tải DB.
- Đề xuất: Chỉ tin `X-Forwarded-For` khi `remoteAddr` thuộc dải proxy tin cậy (hoặc dùng `ForwardedHeaderFilter`/`server.forward-headers-strategy`); so khớp path bằng `UrlPathHelper`/decoded path hoặc chuyển check vào sau Spring Security path matching; thêm bucket cho `/api/auth/refresh`. Cân nhắc thêm key theo username bên cạnh IP.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`459380c`) — (1) property `rate-limit.trusted-proxies` (mặc định rỗng = luôn dùng remoteAddr); chỉ khi remoteAddr thuộc danh sách mới đọc X-Forwarded-For, và lấy **phần tử cuối** (do trusted proxy append, không giả mạo được). (2) So khớp path đã decode qua `UrlPathHelper.getPathWithinApplication` + `equals`. (3) Thêm bucket `/api/auth/refresh` (mặc định 20 req/60s). Key theo username chưa làm — để backlog.

### [MEDIUM] RateLimitFilter không có graceful degradation khi Redis down
- Vị trí: `RateLimitFilter.java:89-104`, `RateLimiterConfig.java:18-31`
- Mô tả: Các evaluator đều bọc try-catch cho Redis, nhưng `bucket.tryConsumeAndReturnRemaining(1)` gọi Redis trực tiếp không có try-catch. Redis down → exception → **toàn bộ login/register trả 500** (fail-closed ngoài ý muốn), trong khi phần authorization lại fail-open về DB.
- Tác động: Redis outage làm sập hoàn toàn chức năng đăng nhập thay vì chỉ mất rate limiting.
- Đề xuất: Bọc try-catch quanh thao tác bucket; khi Redis lỗi thì log warning và cho request đi qua (fail-open, nhất quán với triết lý graceful degradation đã chọn), hoặc chủ đích fail-closed có tài liệu hóa.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`459380c`) — thao tác bucket bọc try-catch, Redis lỗi → `log.warn` + cho request đi qua (fail-open, nhất quán với 2 evaluator).

### [MEDIUM] Evict cache role được gọi bên trong transaction, trước commit — trái với chính Javadoc của evaluator
- Vị trí: `StoreService.java:162,186,211`; Javadoc yêu cầu tại `StoreAccessEvaluator.java:97-101`
- Mô tả: `evictStoreRoleCache` được gọi giữa method `@Transactional`, trước khi commit. Request khác chen vào giữa (sau evict, trước commit) sẽ cache-miss → đọc DB (dữ liệu cũ chưa commit) → **ghi lại role cũ vào cache** với TTL 300s. Javadoc của evaluator ghi rõ "Phải gọi sau khi transaction DB commit" nhưng code không làm vậy.
- Tác động: Sau remove/demote member, quyền cũ có thể "hồi sinh" trong cache tới 5 phút.
- Đề xuất: Dùng `TransactionSynchronizationManager.registerSynchronization(afterCommit -> evict...)` hoặc tách evict ra sau khi method transactional trả về (ở controller/facade), hoặc dùng `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`f1f7e23`) — helper `evictMemberCachesAfterCommit` trong `StoreService` dùng `TransactionSynchronizationManager.registerSynchronization(afterCommit)`; `UserService.deleteUser` (`04a09d4`) dùng cùng pattern.

### [MEDIUM] Secrets mặc định commit trong repo + `admin.seed.enabled=true` trái với tài liệu
- Vị trí: `application.properties:19` (`jwt.secret` có default Base64 thật), `application.properties:85-88` (`admin.seed.enabled=true`, password fallback `Admin@123`), `application.properties:6` (DB password fallback)
- Mô tả: SECURITY.md mục 8 nói `admin.seed.enabled` "mặc định false, chỉ bật khi cần seed lần đầu — tắt ngay sau đó" nhưng file cấu hình đang để `true`. `jwt.secret` fallback là một key thật commit vào repo — nếu prod quên set `JWT_SECRET`, bất kỳ ai đọc được repo đều **tự ký được JWT với roles=["ROLE_SUPER_ADMIN"]**.
- Tác động: Rủi ro chiếm quyền SUPER_ADMIN nếu env var thiếu ở prod; seed admin với mật khẩu yếu mặc định.
- Đề xuất: Bỏ default cho `jwt.secret` (fail-fast khi thiếu) hoặc chuyển default sang profile `local`; đặt `admin.seed.enabled=false` mặc định như tài liệu; validate độ dài key ≥32 bytes lúc khởi động.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`606df9f`) — lưu ý tiền đề sai: `application.properties` nằm trong `.gitignore` nên **không hề được commit trong repo** — secret chỉ tồn tại ở file local của dev. Đã làm: (1) `ApplicationConfig.decodeAndValidateJwtSecret` fail-fast lúc boot nếu `jwt.secret` rỗng hoặc <32 bytes sau Base64 decode; (2) file local đặt `admin.seed.enabled=false` khớp SECURITY.md; (3) SECURITY.md mục 9 bổ sung ghi chú startup validation + gitignore.

### [MEDIUM] Soft-delete user không giải phóng username/email và không thu hồi role
- Vị trí: `UserService.java:91-101`, `User.java:13-16` (unique index không kèm điều kiện `deleted_at IS NULL`)
- Mô tả: `deleteUser` chỉ set `deletedAt`/`isActive=false`. Unique index trên `username`/`email` là index thường → người mới không thể đăng ký lại email đó (`Username or email already taken`). Ngoài ra các `user_roles` của user bị xóa không bị soft-delete/deactivate.
- Tác động: Data correctness — email "bị chiếm vĩnh viễn" bởi tài khoản đã xóa; role rác còn active trong DB (kết hợp lỗi CRITICAL #2 thì user đã xóa vẫn pass mọi evaluator vì `findActiveStoreRole`/`findActiveBusinessRole` vẫn thấy role).
- Đề xuất: Khi delete: revoke refresh tokens, soft-delete các `user_roles`, cân nhắc partial unique index `WHERE deleted_at IS NULL` (PostgreSQL) và đổi username/email thành giá trị tombstone.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`04a09d4`) — `deleteUser` revoke toàn bộ refresh token + `softDeleteAllByUserId` cho `user_roles` + evict store/business cache after-commit; migration `V7__users_partial_unique_index.sql` chuyển unique username/email sang partial index `WHERE deleted_at IS NULL` (DROP constraint cũ có chủ đích không dùng IF EXISTS — fail loudly nếu tên constraint lệch). Tombstone username/email không làm — partial index đã giải phóng định danh.

### [LOW] Check unique username/email kiểu check-then-act (TOCTOU)
- Vị trí: `UserService.java:103-110`
- Mô tả: `checkUsernameAndEmailUnique` rồi mới save — hai request song song cùng đổi về một username đều pass check; DB unique constraint chặn được nhưng trả về `DataIntegrityViolationException` (500) thay vì lỗi 400 thân thiện. `AuthService.register` đã xử lý đúng pattern này (catch DIVE) — nên làm tương tự.
- Tác động: Lỗi 500 không thân thiện trong trường hợp hiếm.
- Đề xuất: Catch `DataIntegrityViolationException` quanh save và convert thành `IllegalArgumentException` như register.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`04a09d4`) — `updateProfile`/`updateUser` dùng `saveAndFlush` trong try-catch `DataIntegrityViolationException` → `IllegalArgumentException("Username or email already taken")` (flush phải nằm trong try — flush lúc commit sẽ thoát khỏi catch).

### [LOW] Tài liệu mô tả sai JWT claim roles và authority
- Vị trí: `docs/SECURITY.md:53-54, 92-93`, `docs/TOKEN_LIFECYCLE.md:44-46` so với `AuthService.java:153-158` và `RoleName.java`
- Mô tả: Docs viết claim `roles: ["SUPER_ADMIN"]` và authority `"SUPER_ADMIN"`. Thực tế enum là `ROLE_SUPER_ADMIN` nên claim/authority là `"ROLE_SUPER_ADMIN"`. Code vẫn chạy đúng (`hasRole('SUPER_ADMIN')` tự thêm prefix `ROLE_`, evaluator so sánh `RoleName.ROLE_SUPER_ADMIN.name()`), nhưng ai code theo docs (ví dụ `.anyMatch(a -> "SUPER_ADMIN".equals(...))`) sẽ sai. Ngoài ra comment trong `RoleName.java:5` ghi OWNER là "store-scoped" trong khi docs/DB định nghĩa OWNER là business-scoped.
- Tác động: Nguy cơ bug tương lai do docs lệch code.
- Đề xuất: Sửa docs cho khớp giá trị thật `ROLE_*`; sửa comment enum.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`0631a54`) — SECURITY.md (bảng role, claim example, authorities) và TOKEN_LIFECYCLE.md (payload example, bảng claim, các sơ đồ luồng) sửa hết thành `ROLE_*`, kèm ghi chú `hasRole('SUPER_ADMIN')` tự thêm prefix. Comment `RoleName.java:5` đã được sửa đúng "business-scoped" từ trước tại `1f28701` (đợt xử lý 01_database_schema.md).

### [LOW] Không có job dọn refresh token hết hạn / bị revoke
- Vị trí: `RefreshTokenRepository.java` (chỉ có `findByToken`, `revokeAllByUserId`); không có `@Scheduled` cleanup nào trong codebase
- Mô tả: Mỗi login tạo 1 row mới, mỗi refresh (24h/lần) tạo thêm 1 row; row cũ chỉ được đánh dấu revoke, không bao giờ xóa.
- Tác động: Bảng `refresh_tokens` phình vô hạn theo thời gian.
- Đề xuất: Thêm scheduled job xóa token có `expires_at < now - X ngày`.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`0631a54`) — `RefreshTokenCleanupScheduler` chạy 02:00 AM hàng ngày (`refresh-token.cleanup.cron`), xóa cứng token có `expires_at < now - retention-days` (mặc định 7). Chỉ xét `expires_at`, KHÔNG xóa token revoked-chưa-hết-hạn — chúng là "bẫy" cho reuse-detection. TOKEN_LIFECYCLE.md mục 9 bổ sung cấu hình.

### [LOW] Response 401 của entry point không theo format ApiResult
- Vị trí: `SecurityConfig.java:78-81`
- Mô tả: `response.sendError(401, "Unauthorized")` trả về trang lỗi mặc định của container (HTML/JSON tùy config), không khớp envelope `{"success":false,"error":{...}}` mà RateLimitFilter và GlobalExceptionHandler đang dùng.
- Tác động: Client phải xử lý 2 format lỗi khác nhau cho cùng nhóm lỗi auth.
- Đề xuất: Ghi thẳng JSON `ApiResult` như cách `RateLimitFilter` làm.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** (`0631a54`) — entry point ghi thẳng body JSON `{"success":false,"data":null,"error":{"code":"UNAUTHORIZED",...}}` (cùng pattern hằng số như `RATE_LIMIT_BODY` của RateLimitFilter), khớp error code `UNAUTHORIZED` đã document trong docs/api.

### [LOW] `AuthService.login` dùng `orElseThrow()` không thông điệp
- Vị trí: `AuthService.java:77-78`
- Mô tả: Sau `authenticate()` thành công thì user chắc chắn tồn tại, nhưng nếu có race (user bị xóa giữa 2 câu lệnh) sẽ ném `NoSuchElementException` → 500 khó truy vết. Cùng pattern tại `AuthService.java:87` (refresh).
- Tác động: Edge case hiếm, lỗi 500 không rõ nghĩa.
- Đề xuất: Dùng `orElseThrow(() -> new IllegalStateException(...))` hoặc exception nghiệp vụ có message.
- **Trạng thái (2026-07-09):** ✅ **Đã xử lý** — occurrence ở `refresh()` sửa từ `2d2a8d8` (thay bằng `orElse(null)` + check user status); occurrence ở `login()` sửa tại `0631a54` (`IllegalStateException` có message rõ ràng).

## 4. Điểm tốt

- **Refresh token rotation + ý tưởng reuse-detection** đúng chuẩn hiện đại (chỉ hỏng vì transaction rollback — dễ sửa); token opaque 256-bit entropy (2x UUID), lưu DB, có `revokedAt` thay vì hard delete (giữ audit trail).
- **JWT tối giản, 0 DB call mỗi request**: chỉ nhúng global roles vào token, roles business/store-scoped check qua evaluator — quyết định thiết kế đúng, được giải thích rõ trong docs (tránh token phình và stale role).
- **Graceful degradation Redis** trong cả 2 evaluator: mọi thao tác Redis bọc try-catch, fallback DB — hệ thống phân quyền không chết theo Redis.
- **Rate limit phân tán** qua Bucket4j + Redis (đúng cho multi-instance), trả 429 kèm `Retry-After` và body đúng format `ApiResult`.
- CORS cấu hình whitelist origin + header cụ thể, không dùng wildcard với `allowCredentials(true)`.
- `User.isEnabled()` gộp cả `isActive` và `deletedAt` — login chặn đúng user bị khóa/xóa; lỗi `UsernameNotFoundException` được Spring che thành `BadCredentialsException` (không lộ user tồn tại).
- Header `JwsHeader HS256` tường minh khi encode (tránh lỗi chọn key của Nimbus), decoder pin thuật toán HS256 — chống algorithm confusion.
- `UserPrincipalConverter.extractUserId` normalize Integer/Long cẩn thận; `UserPrincipal` là record immutable.
- Register xử lý duplicate qua catch `DataIntegrityViolationException` (an toàn race) thay vì check-then-act.
- `AdminUserController` có `@PreAuthorize("hasRole('SUPER_ADMIN')")` cấp class — không sót endpoint.
- Tài liệu SECURITY.md / TOKEN_LIFECYCLE.md chi tiết bất thường (theo hướng tốt), có bảng chi phí DB/Redis từng scenario và ghi rõ trade-off của JWT stateless.

## 5. Kết luận & ưu tiên xử lý

Nền tảng thiết kế tốt nhưng có 2 lỗi CRITICAL về vòng đời token làm sai lệch hoàn toàn mô hình bảo mật đã tài liệu hóa. Thứ tự xử lý đề xuất:

1. **[CRITICAL] Sửa rollback trong `rotate()`** — dùng `REQUIRES_NEW` hoặc `noRollbackFor` để revoke-all và đánh dấu expired thực sự persist. (Nhỏ, tác động lớn.)
2. **[CRITICAL] Chặn user bị khóa/xóa ở luồng refresh + revoke refresh token khi deactivate/delete** — vá lỗ hổng offboarding.
3. **[HIGH] Revoke refresh token khi đổi mật khẩu.**
4. **[HIGH] Gọi `evictBusinessMemberCache`/`evictBusinessRoleCache` tại các điểm thay đổi membership** (đồng thời chuyển evict sang after-commit — gộp với mục MEDIUM tương ứng).
5. **[HIGH] Cấm username dạng email** (validation `@Pattern`) — tránh xung đột `findByUsernameOrEmail`.
6. **[HIGH] Khóa chống race trong `rotate()`** (atomic UPDATE hoặc pessimistic lock).
7. **[MEDIUM] Giới hạn password ≤72 bytes**; **vá bypass rate limit** (X-Forwarded-For, URI encode, thêm `/refresh`); **try-catch Redis trong RateLimitFilter**; **bỏ default `jwt.secret` và tắt `admin.seed.enabled`**.
8. **[LOW]** Các mục còn lại (cleanup job, format 401, docs lệch code, TOCTOU profile update) xử lý dần theo sprint.

Sau khi sửa nhóm 1–3, nên bổ sung integration test cho: refresh với token đã revoke (assert revoke-all persist), refresh với user inactive (assert 401), và đổi mật khẩu rồi refresh bằng token cũ (assert 401).

> **Trạng thái (2026-07-09):** Toàn bộ 17 mục đã xử lý (xem dòng Trạng thái từng mục). ⚠️ Còn mở: bộ integration test đề xuất ở trên chưa viết; rate-limit key theo username chưa làm.
