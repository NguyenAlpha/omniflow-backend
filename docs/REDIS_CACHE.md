# Redis Cache — Role Authorization

Giải thích cơ chế cache Redis dùng để tăng tốc kiểm tra quyền truy cập.

> **Phạm vi:** doc này chỉ nói về cache phân quyền. Redis còn được dùng cho:
> - **Rate limit** — bucket Bucket4j, key `rl:*` (VD `rl:login:<ip>`, `rl:user:api:<userId>`,
>   `rl:login-account:user:<userId>`) — xem [RATE_LIMITING.md](RATE_LIMITING.md)
> - **Idempotency khi tạo đơn** — key `idem:<userId>:<storeId>:<Idempotency-Key>`, lưu response 24h —
>   xem [FEATURES.md](FEATURES.md) mục Idempotency Key
>
> Các key đó có TTL và cơ chế lỗi Redis riêng, không theo các quy tắc bên dưới.

---

## 1. Vấn đề không có cache

Mỗi request cần kiểm tra quyền đều phải hỏi database:

```
GET /api/stores/1/orders
→ "User này có phải OWNER không?"  → SELECT từ user_roles   [1 DB]
→ "User này có phải MANAGER không?" → SELECT từ user_roles  [1 DB]
```

Nếu user gọi 100 request/phút → 200 DB query chỉ để kiểm tra quyền — trong khi
role của user gần như không thay đổi giữa các request.

Redis cache giải quyết điều này: **hỏi DB 1 lần, lưu kết quả vào Redis, các request
tiếp theo đọc Redis thay vì DB.**

---

## 2. Những gì được cache

Có 4 loại key đang được cache:

| Key | Giá trị | Ý nghĩa |
|:----|:--------|:--------|
| `business:role:{userId}:{businessId}` | `"ROLE_OWNER"` hoặc `"ROLE_BUSINESS_MANAGER"` | Role cấp business của user (chủ hoặc trợ lý) |
| `business:member:{userId}:{businessId}` | `"ROLE_MANAGER"` hoặc `"ROLE_STAFF"` | Role cao nhất của user qua store membership trong business |
| `store:role:{userId}:{storeId}` | `"ROLE_MANAGER"` hoặc `"ROLE_STAFF"` | Role của user trong store cụ thể này |
| `store:business:{storeId}` | `"42"` (businessId dạng string) | Store này thuộc business nào |

Tất cả đều dùng TTL 300 giây (5 phút) — cấu hình bởi `store.role.cache.ttl`.

---

## 3. Cache hoạt động như thế nào

### Luồng cơ bản: Read → fallback DB → Write

```
Có key trong Redis?
  ├── CÓ (cache hit)  → đọc giá trị → dùng ngay, không hỏi DB
  └── KHÔNG (cache miss) → hỏi DB → lưu kết quả vào Redis → dùng
```

Ví dụ cho `business:role`:

```
Request 1 (lần đầu):
  Redis GET "business:role:5:3"  → null (chưa có)
  DB SELECT ... WHERE user_id=5 AND business_id=3  → OWNER
  Redis SET "business:role:5:3" = "ROLE_OWNER" TTL 300s
  → return true

Request 2, 3, 4... (trong 300 giây):
  Redis GET "business:role:5:3"  → "ROLE_OWNER" (cache hit)
  → return true  (0 DB call)

Sau 300 giây:
  Key tự xóa (expire) → Request tiếp theo lại hỏi DB
```

### Chỉ cache kết quả positive

Cache chỉ lưu khi user **có** quyền (OWNER / MANAGER / STAFF).
Nếu user **không có** quyền → không lưu → mỗi request đó vẫn hỏi DB.

Lý do: non-member hiếm khi gọi endpoint bảo vệ thành công, nên không đáng cache.
Nếu cache "NONE" thì khi user được cấp quyền mới, phải chủ động xóa cache đó.

---

## 4. TTL — tự expire sau 300 giây

TTL (Time To Live) là thời gian Redis tự xóa key. Sau 300s:
- Cache miss xảy ra
- Request tiếp theo hỏi DB
- Kết quả mới được cache lại

Điều này có nghĩa: nếu role bị thay đổi, **worst case** user cũ/mới vẫn dùng
cache cũ trong tối đa 300 giây — trừ khi cache được evict thủ công (xem mục 5).

---

## 5. Cache invalidation — xóa cache khi role thay đổi

Khi role của user thay đổi (cấp quyền, thu hồi quyền, transfer ownership),
cache cũ phải được xóa ngay — không chờ TTL expire.

```java
// Sau khi DB commit thành công:
businessAccess.evictBusinessRoleCache(userId, businessId);  // xóa business:role (thay đổi OWNER)
businessAccess.evictBusinessMemberCache(userId, businessId); // xóa business:member (thay đổi store member)
storeAccess.evictStoreRoleCache(userId, storeId);           // xóa store:role (thay đổi store role)
```

**Quan trọng:** phải gọi **sau khi transaction DB đã commit**.
Nếu gọi trước commit → cache bị xóa → request tiếp theo đọc DB → thấy data cũ
(transaction chưa commit) → cache lại data cũ → sai.

---

## 6. Graceful degradation — Redis bị tắt

Mọi thao tác Redis đều được bọc `try-catch`. Khi Redis không khả dụng:

```java
try {
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return true;
} catch (Exception ignored) {
    // Redis down → bỏ qua, tiếp tục hỏi DB
}

// ... hỏi DB như bình thường ...

try {
    redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS);
} catch (Exception ignored) {
    // Ghi cache thất bại → bỏ qua, DB đã trả kết quả rồi
}
```

Kết quả: Redis down → mọi request fallback về DB → hệ thống chậm hơn nhưng
**không bị lỗi**.

---

## 7. Tổng hợp DB calls theo scenario

### Store endpoint (`@storeAccess`)

| Scenario | DB | Redis |
|:---------|:--:|:-----:|
| SUPER_ADMIN | 0 | 0 |
| OWNER — store:business cache hit, business:role cache hit | 0 | 2 read |
| OWNER — store:business cache hit, business:role cache miss | 1 | 1 read + 1 write |
| OWNER — store:business cache miss | 2 | 1 read + 2 write |
| MANAGER/STAFF — business:role + store:role cache hit | 0 | 2 read |
| MANAGER/STAFF — business:role cache hit, store:role cache miss | 1 | 1 read + 1 write |
| MANAGER/STAFF — business:role cache miss, store:role cache hit | 1 | 2 read |
| MANAGER/STAFF — tất cả cache miss | 2 | 2 read + 2 write |

*OWNER check luôn được thực hiện trước store role check (early exit nếu OWNER)

### Business/catalog endpoint (`@businessAccess`)

| Scenario | DB | Redis |
|:---------|:--:|:-----:|
| SUPER_ADMIN | 0 | 0 |
| OWNER — business:role cache hit | 0 | 1 read |
| OWNER — business:role cache miss | 1 | 1 read + 1 write |
| MANAGER/STAFF — business:role + business:member cache hit | 0 | 2 read |
| MANAGER/STAFF — business:role cache hit, business:member cache miss | 1 | 1 read + 1 write |
| MANAGER/STAFF — tất cả cache miss | 2 | 2 read + 2 write |

---

## 8. Lưu ý về `store:business` key

Key `store:business:{storeId}` lưu businessId của một store — dùng để
`StoreAccessEvaluator` biết cần check OWNER của business nào mà không phải JOIN.

Key này **không bao giờ thay đổi** (store không thể chuyển sang business khác),
nên TTL 300s chỉ là để giới hạn bộ nhớ Redis — không phải vì lo stale data.
Có thể đặt TTL dài hơn nếu muốn.
