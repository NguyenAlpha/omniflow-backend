# /doc-code

Thêm Javadoc và comment giải thích cho một file Java, để người mới đọc hiểu ngay code đang làm gì
— đặc biệt là các lời gọi sang service/framework khác và các thao tác không hiển nhiên.

**Chỉ thêm/sửa comment — không đổi bất kỳ dòng code nào.**

## Cách dùng

```
/doc-code <đường dẫn file hoặc tên class> [--learn]
```

Ví dụ: `/doc-code AuthService` hoặc `/doc-code src/main/java/com/quiktech/pos/service/OrderService.java --learn`

Tham số: $ARGUMENTS

### Hai loại comment

| Loại | Viết khi nào | Ví dụ |
|:---|:---|:---|
| **Giải thích dự án** — vì sao code của dự án này làm vậy, lời gọi sang class khác dẫn tới gì | **Luôn viết** | Để unique index chặn trùng thay vì check trước (tránh race); `authenticate()` tìm user → so BCrypt → kiểm tra khóa |
| **Kiến thức Java/Spring chung** — cú pháp, thư viện chuẩn, đúng ở mọi dự án | **Chỉ khi có `--learn`** | `Boolean.TRUE.equals(x)` tránh NPE; `.name()` đổi enum → chuỗi; `LinkedHashMap` giữ thứ tự chèn |

Cách phân biệt: nếu comment vẫn đúng khi bê sang một dự án Java bất kỳ → đó là kiến thức chung.

Comment loại `--learn` đã có sẵn trong file thì **giữ nguyên** khi chạy không có `--learn`
(không xóa comment cũ).

---

## Quy trình thực hiện

1. **Đọc file cần comment** (class trong `src/main/java/com/quiktech/pos/**`).
2. **Đọc code được gọi tới trước khi viết — không đoán.** Với mỗi lời gọi sang class khác
   (service, repository, security, Spring Security/Spring Data…), mở class đó ra xem nó thật sự làm
   gì, ném exception gì. Nguồn cần xem:
   - Method được gọi và Javadoc sẵn có của nó (VD `RefreshTokenService.rotate`, `ApplicationConfig.userDetailsService`)
   - Query của repository (`@Query`, tên method Spring Data) — lọc gì, `JOIN FETCH` gì
   - `exception/GlobalExceptionHandler.java` — exception nào thành HTTP status / `ErrorCode` nào
   - `application.properties` — giá trị cấu hình và đơn vị (ms, giây, ngày)
   - Migration `db/migration/*.sql` — unique index, constraint mà code dựa vào
3. **Viết Javadoc + comment** theo chuẩn bên dưới.
4. **Kiểm tra**: `./mvnw -q -DskipTests compile` phải pass; chạy test liên quan tới class (nếu có).
   Dùng `git diff` xác nhận chỉ có dòng comment thay đổi.
5. **Báo cáo** cho người dùng: liệt kê những chỗ đã thêm comment (theo method) và comment cũ nào
   đã sửa vì không còn đúng.

---

## Chuẩn Javadoc

Ngôn ngữ **tiếng Việt**; tên class, method, field, giá trị enum giữ nguyên tiếng Anh.

**Javadoc đầu class** — class này lo việc gì, và kiến thức nền cần có để đọc các method bên dưới
(VD có mấy loại token, có mấy nguồn quyền). Nếu một phần việc nằm ở class khác thì `{@link}` tới đó.

**Javadoc cho mọi method public và mọi method (kể cả private) có logic đáng giải thích.**
Bỏ qua: getter/setter, record/DTO/entity chỉ chứa field, method 1–2 dòng chỉ chuyển tiếp lời gọi
mà tên đã nói rõ — Javadoc kiểu `/** Lấy tên. @return tên */` chỉ gây nhiễu.

Javadoc gồm:
- Câu đầu: method làm gì, viết theo góc nhìn nghiệp vụ (không lặp lại tên method)
- `<p>` thêm ngữ cảnh khi cần: thứ tự các bước, khi nào được gọi, hệ quả phụ (thu hồi token, ghi audit…)
- `@param` — **ý nghĩa** của tham số, không chỉ lặp lại kiểu dữ liệu
- `@return` — trả về gì, và khi nào rỗng / null
- `@throws` — exception nào, khi nào, kèm HTTP status mà `GlobalExceptionHandler` trả về (`→ 401`)

Ví dụ:

```java
/**
 * Đăng nhập bằng username hoặc email.
 *
 * <p>Thứ tự: kiểm tra còn lượt đăng nhập → kiểm tra mật khẩu → tạo refresh token mới
 * → dựng response. Mỗi lần sai mật khẩu trừ 1 lượt của tài khoản (xem {@link LoginAttemptLimiter}).
 *
 * @param request username hoặc email + mật khẩu
 * @return access token, refresh token mới, thông tin user và memberships
 * @throws com.quiktech.pos.exception.RateLimitExceededException hết lượt đăng nhập sai (→ 429)
 * @throws BadCredentialsException sai username/email hoặc mật khẩu (→ 401)
 */
```

Method đã có Javadoc giải thích kỹ thuật (VD lý do dùng `noRollbackFor`) → **giữ nguyên phần đó**,
chỉ thêm câu mô tả nghiệp vụ lên đầu và bổ sung `@param`/`@return`/`@throws` còn thiếu.

---

## Chuẩn comment trong thân method

Comment giải thích **"cái gì xảy ra bên trong / tại sao"**, không đọc lại code. Ưu tiên comment ở:

| Chỗ cần comment | Nên viết gì | Ví dụ |
|:---|:---|:---|
| Gọi sang service/framework khác | Bên trong nó làm những bước gì, ném lỗi gì | `authenticationManager.authenticate()`: tìm user → so BCrypt → kiểm tra khóa |
| Class/object có tên dễ hiểu nhầm | Nó thực chất là gì | `UsernamePasswordAuthenticationToken` chỉ là "gói" tên + mật khẩu, không phải JWT |
| Quyết định thiết kế | Vì sao làm vậy, cách khác sai ở đâu | Không check trùng trước khi `save()` — để unique index chặn, tránh race |
| Exception được bắt / ném lại | Sẽ thành HTTP status nào | `IllegalArgumentException` → 400 `VALIDATION_ERROR` |
| Đổi đơn vị, giá trị cấu hình | Tên property + đơn vị (xem "Tránh comment bị lỗi thời") | `jwtExpiration` (`jwt.expiration`, mili giây) → `/ 1000` ra giây |
| Stream / collect phức tạp | Kết quả trung gian có dạng gì | `groupingBy` → `Map<businessId, List<UserRole>>` |
| Lựa chọn cấu trúc dữ liệu vì lý do của dự án | Vì sao cần ở đây | `LinkedHashMap` để thứ tự memberships ổn định giữa các lần login; `Map` để tra nhanh tránh N+1 |
| Nhánh phòng thủ | Phòng trường hợp dữ liệu gì | `m != null ? … : null` khi có role nhưng thiếu bản ghi `store_members` |
| Truyền tham số lạ | Vì sao | Truyền cùng giá trị 2 lần vì query là `username = ? OR email = ?` |
| Hiệu năng chấp nhận được | Ghi rõ để người sau không "sửa" nhầm | 1 query/business — chấp nhận vì user chỉ có vài business |

**Không comment** những dòng tự giải thích: builder gán field, getter/setter, `return` đơn giản, log.

Cách viết:
- Ngắn gọn, 1–3 dòng; dùng `→` cho quan hệ nguyên nhân/kết quả
- Nhắc đúng tên class/method/bảng/index thật để người đọc tìm được (`uq_users_email_active`, `UserPrincipalConverter`)
- Trong Javadoc dùng `{@link Class#method}` / `{@code ...}`; trong comment thường dùng tên trơn

---

## Tránh comment bị lỗi thời

Comment sai còn tệ hơn không có comment. Comment hay bị sai nhất khi **chép lại một sự thật đang
nằm ở chỗ khác** — chỗ kia đổi, comment không tự đổi theo.

- **Con số cấu hình**: ghi **tên property**, không ghi cứng giá trị. Nếu cần ví dụ thì ghi rõ "mặc định".
  - ❌ `// Hết lượt (5 lần sai / 15 phút) → 429`
  - ✅ `// Hết lượt (rate-limit.login-account.*, mặc định 5 lần sai / 15 phút) → 429`
- **Hành vi của class khác**: tóm tắt 1 dòng ở mức "dẫn tới gì" rồi trỏ tới class đó (`xem RefreshTokenService.rotate`);
  chỉ liệt kê từng bước khi đó là thư viện/framework (Spring Security…) mà người đọc không mở ra xem được trong repo.
- **Tên bảng, index, error code**: ghi đúng tên thật (tìm được bằng search), không diễn đạt lại.

---

## Quy tắc bắt buộc

- **Không đổi code**: chỉ thêm/sửa dòng comment và Javadoc.
- **Không xóa comment cũ.** Comment cũ không còn đúng với code (VD ghi "OWNER" nhưng query lấy cả
  `BUSINESS_MANAGER`) → **sửa lại cho đúng** và nêu ra trong báo cáo.
- **Mọi điều viết ra phải kiểm chứng được trong code** (bước 2). Không chắc thì đọc thêm code,
  không viết theo phỏng đoán.
- Phát hiện bug hoặc điểm đáng cải thiện trong lúc đọc → **không sửa**, chỉ nêu trong báo cáo.
