# Đánh giá Business / Store / Subscription — 2026-07-05

> **Cập nhật 2026-07-10:** Toàn bộ vấn đề trong tài liệu này đã được xử lý qua các commit
> `ef50a96` (member/IDOR), `375c76c` (vòng đời subscription), `4429f02` (race), `de4c8d4` (LOW),
> `53239af` (dead code + isActive docs). Riêng 2 vấn đề cache evict đã fix trước đó ở đợt 02
> (`f1f7e23`). Trạng thái chi tiết ghi dưới từng mục.

## 1. Phạm vi đã kiểm tra (liệt kê file)

- `api/src/main/java/com/quiktech/backend/controller/BusinessController.java`
- `api/src/main/java/com/quiktech/backend/controller/StoreController.java`
- `api/src/main/java/com/quiktech/backend/controller/SubscriptionController.java`
- `api/src/main/java/com/quiktech/backend/service/BusinessService.java`
- `api/src/main/java/com/quiktech/backend/service/StoreService.java`
- `api/src/main/java/com/quiktech/backend/service/SubscriptionService.java`
- `api/src/main/java/com/quiktech/backend/service/SubscriptionLimitService.java`
- `api/src/main/java/com/quiktech/backend/service/SubscriptionExpiryScheduler.java`
- `api/src/main/java/com/quiktech/backend/security/BusinessAccessEvaluator.java` (khía cạnh evict/consistency)
- `api/src/main/java/com/quiktech/backend/security/StoreAccessEvaluator.java` (khía cạnh evict/consistency)
- `api/src/main/java/com/quiktech/backend/entity/{Business, Store, StoreMember, BusinessMember, UserRole, Subscription, SubscriptionInvoice}.java`
- `api/src/main/java/com/quiktech/backend/entity/enums/{PlanLimits, PlanPricing, SubscriptionPlan, SubscriptionStatus, InvoiceStatus, BillingCycle, RoleName}.java`
- `api/src/main/java/com/quiktech/backend/repository/{BusinessRepository, BusinessMemberRepository, StoreRepository, StoreMemberRepository, UserRoleRepository, SubscriptionRepository, SubscriptionInvoiceRepository, WarehouseRepository, OrderRepository}.java`
- `api/src/main/java/com/quiktech/backend/dto/request/store/{AddMemberRequest, UpdateMemberRequest, StoreCreateRequest}.java`
- `api/src/main/java/com/quiktech/backend/dto/request/subscription/{UpgradeRequest, DowngradeRequest, ChangePlanRequest}.java`
- `api/src/main/java/com/quiktech/backend/config/seed/BusinessSeeder.java` (phần seed subscription)
- `api/src/main/resources/application.properties`
- `api/docs/STORE_MEMBER_LIFECYCLE.md`, `api/docs/LIFECYCLE.md`, `api/docs/FEATURES.md`, `api/docs/REDIS_CACHE.md`

> Không có entity/flow Invitation trong codebase — member được OWNER thêm trực tiếp bằng `userId`.
> Không có annotation/aspect riêng cho plan limit (`@CheckPlanLimit` không tồn tại) — limit được check bằng gọi tay `SubscriptionLimitService` trong từng service.

## 2. Tổng quan nhận xét

Kiến trúc module gọn và nhất quán với thiết kế 2 tầng quyền (OWNER ở business level qua `user_roles.business_id`, MANAGER/STAFF ở store level), member được biểu diễn song song bằng `StoreMember` (metadata) + `UserRole` (authorization) đúng như mô tả trong `STORE_MEMBER_LIFECYCLE.md`. Enforcement giới hạn gói tập trung ở `SubscriptionLimitService` và được gọi tại cả 5 điểm tạo resource (store, staff, product, warehouse, order). Luồng upgrade qua invoice chuyển khoản + admin confirm có kiểm tra tenant (`getInvoiceForBusiness`) đúng.

Tuy nhiên có **1 lỗi IDOR nghiêm trọng**: `updateMember`/`removeMember` không kiểm tra member có thuộc store trong URL hay không — OWNER của business bất kỳ có thể xóa/sửa member của business khác chỉ bằng cách đoán `memberId`. Về subscription, **vòng đời sau khi hết hạn gần như không hoạt động**: subscription EXPIRED vẫn giữ nguyên limit của gói trả phí (PRO = unlimited) vô thời hạn vì các hàm check limit không nhìn `status`/`expiresAt`, đồng thời user hết hạn **không thể gia hạn lại chính gói cũ** do `requestUpgrade` yêu cầu gói mới phải cao hơn gói hiện tại. Ngoài ra check duplicate member có thể bypass bằng `isActive=false`, tạo được bản ghi trùng làm hỏng luôn cơ chế phân quyền của user đó.

Các vấn đề cache đã nêu ở phần 02 (`business:role`/`business:member` không bao giờ evict) được xác nhận lại là **sai lệch trực tiếp với `STORE_MEMBER_LIFECYCLE.md` mục 3c/3d/5** — tài liệu mô tả evict `business:member` là "bắt buộc" nhưng code không gọi; phần này chỉ nhắc ngắn, không lặp lại chi tiết.

## 3. Vấn đề phát hiện

### [CRITICAL] IDOR: updateMember / removeMember không kiểm tra member thuộc store — xóa/sửa được member của business khác
- Vị trí: `StoreService.java:171-172` (updateMember), `StoreService.java:195-196` (removeMember); controller `StoreController.java:81-98`
- Mô tả: Cả hai method load member bằng `storeMemberRepository.findById(memberId)` và **không hề so sánh `member.getStore().getId()` với `storeId`** trên URL. `@PreAuthorize("@storeAccess.isOwner(#storeId)")` chỉ xác nhận caller là OWNER của store *trong URL* — không liên quan gì đến store của member. Kịch bản: kẻ tấn công là OWNER hợp lệ của business A, gọi `DELETE /api/stores/{storeIdCủaA}/members/{memberIdCủaBusinessB}` → PreAuthorize pass, `findById` tìm thấy member của business B → `deletedAt` được set → nhân viên của business B bị đuổi khỏi store. Với `updateMember`, nếu victim user tình cờ (hoặc bị chủ động add) có role trong store của kẻ tấn công thì `positionTitle`/`isActive` của StoreMember thuộc business B cũng bị ghi đè chéo tenant. `memberId` là số tự tăng nên đoán được dễ dàng.
- Tác động: Phá vỡ hoàn toàn multi-tenant isolation cho thao tác quản lý member — bất kỳ ai bỏ 0đ tạo business FREE đều có thể vô hiệu hóa nhân sự của tenant khác (DoS nghiệp vụ POS). Docs `STORE_MEMBER_LIFECYCLE.md` mục 3c/3d cũng mô tả đúng luồng thiếu sót này (chỉ `findById(memberId)`).
- Đề xuất: Thay `findById(memberId)` bằng query scoped `findByIdAndStoreIdAndDeletedAtIsNull(memberId, storeId)` (hoặc check `!member.getStore().getId().equals(storeId)` → 404) trong cả `updateMember` và `removeMember`. Viết test: OWNER store A gọi remove/update với memberId store B → expect 404.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `ef50a96`: cả `updateMember` và `removeMember` load bằng `findByIdAndStoreIdAndDeletedAtIsNull(memberId, storeId)` → 404 khi member không thuộc store trên URL. Test tự động chưa viết (mục còn mở chung).

### [HIGH] Subscription hết hạn vẫn giữ nguyên limit gói trả phí — PRO hết hạn = unlimited vĩnh viễn, không trả tiền
- Vị trí: `SubscriptionLimitService.java:32-85` (mọi hàm check chỉ đọc `sub.getMaxXxx()`, không đọc `status`/`expiresAt`); `SubscriptionRepository.java:46` (`expireOverdue` chỉ set `status`); `SubscriptionExpiryScheduler.java:68-76`
- Mô tả: Khi hết hạn, scheduler chỉ chuyển `status = EXPIRED` (bulk update), **không reset `maxStores/maxStaff/maxProducts/maxWarehouses` về FREE**. Các hàm `checkXxxLimit` lại không hề kiểm tra `status` hay `expiresAt`. Với PRO, limit là `null` (= unlimited theo `PlanLimits.PRO(3, null, null, null)`) → business trả 499k cho 1 tháng PRO, sau đó ngừng trả tiền: status thành EXPIRED nhưng mọi check limit vẫn `return` sớm vì `null` → tạo store/staff/product/warehouse **không giới hạn mãi mãi**. Không có bất kỳ chỗ nào khác trong codebase chặn hành vi theo `SubscriptionStatus.EXPIRED`.
- Tác động: Mất doanh thu trực tiếp — mô hình thu phí bị vô hiệu: chỉ cần mua 1 chu kỳ ngắn nhất là giữ quyền lợi gói vĩnh viễn. Nhánh pending-downgrade (`SubscriptionExpiryScheduler.java:40-61`) xử lý đúng (reset limit theo gói đích), càng cho thấy nhánh bulk-expire bị bỏ sót.
- Đề xuất: (1) Trong scheduler, khi expire sub không có pendingPlan → hạ limit về `PlanLimits.FREE` (soft cap, không xóa data — nhất quán với triết lý downgrade hiện có), hoặc (2) trong `SubscriptionLimitService`, nếu `status != ACTIVE` thì áp limit FREE bất kể giá trị cột. Thêm test: sub PRO EXPIRED → `checkStoreLimit` phải chặn khi đã có ≥1 store.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `375c76c`, theo hướng (1): `expireOverdue` hạ 4 cột limit về `PlanLimits.FREE` ngay trong cùng UPDATE bulk-expire; `plan` giữ nguyên làm record lịch sử. Test tự động chưa viết.

### [HIGH] Không thể gia hạn/tái đăng ký sau khi hết hạn — requestUpgrade yêu cầu gói mới cao hơn gói hiện tại
- Vị trí: `SubscriptionService.java:107-109` (`newPlan.ordinal() <= sub.getPlan().ordinal()` → reject)
- Mô tả: Khi sub hết hạn, `plan` vẫn giữ nguyên (vd PRO) và chỉ `status` đổi thành EXPIRED. Comment trong scheduler (`SubscriptionExpiryScheduler.java:58`: "Paid plan thấp hơn: EXPIRED, user cần re-subscribe") giả định user có thể đăng ký lại — nhưng `requestUpgrade` từ chối mọi gói `<=` gói hiện tại: PRO hết hạn không thể mua lại PRO (equal) hay BASIC (lower). User bị kẹt: gói cao nhất đã ở PRO, không còn đường thanh toán nào qua API ngoài nhờ admin `changePlan` thủ công. Tương tự cho BASIC hết hạn muốn mua lại BASIC.
- Tác động: Khách hàng muốn trả tiền cũng không trả được — chặn toàn bộ luồng renewal, mâu thuẫn với chính comment thiết kế trong code. Kết hợp với vấn đề HIGH phía trên, hệ quả thực tế là "không ai cần renew".
- Đề xuất: Cho phép `requestUpgrade` khi `status != ACTIVE` (hết hạn) với mọi gói trả phí, hoặc chuẩn hóa: khi expire thì hạ `plan` về FREE (kết hợp fix vấn đề trên) — khi đó điều kiện `ordinal` hiện tại tự nhiên đúng. Bổ sung endpoint/nhánh "renew" cho sub ACTIVE sắp hết hạn (hiện cũng không có cách gia hạn trước khi hết hạn).
- **Trạng thái (2026-07-10): ĐÃ FIX (phần chính)** — `375c76c`: check ordinal chỉ áp dụng khi sub ACTIVE; sub EXPIRED mua lại được mọi gói trả phí (thêm guard tường minh chặn FREE). **Còn mở:** nhánh "renew trước khi hết hạn" cho sub ACTIVE chưa có (tính năng mới, ngoài phạm vi fix).

### [HIGH] Bypass check duplicate member bằng isActive=false — tạo bản ghi trùng làm hỏng phân quyền của user
- Vị trí: `StoreService.java:133-134` (check duplicate bằng `findActiveStoreRole` — filter `isActive = true`), `StoreService.java:148,157` (`isActive` lấy từ request), `UserRoleRepository.findActiveStoreRole` (trả `Optional`)
- Mô tả: Check "User is already a member" dùng `findActiveStoreRole` vốn chỉ tìm role `isActive = true`. Nếu member được add với `isActive = false` (request cho phép), hoặc bị `updateMember` set inactive, thì gọi `addMember` lần nữa cho cùng user sẽ **pass check** → tạo thêm 1 cặp `StoreMember` + `UserRole` trùng. Khi cả 2 UserRole cùng active (qua `updateMember`), `findActiveStoreRole` trả 2 rows cho query `Optional` → `IncorrectResultSizeDataAccessException` ném ra ngay trong `StoreAccessEvaluator.resolveStoreRoleWithCache` → **mọi request của user đó vào store bị lỗi 500**, và `updateMember`/`removeMember` cho user đó cũng hỏng. Ngoài ra `getMembers` (`StoreService.java:119-122`) dùng `Collectors.toMap` không có merge function → ném `IllegalStateException` khi có 2 role active cùng userId → API danh sách member sập.
- Tác động: Từ thao tác hợp lệ của OWNER có thể đưa dữ liệu vào trạng thái khiến phân quyền và API member của store lỗi vĩnh viễn (phải sửa tay DB). Không có unique constraint DB nào chặn (index trên `store_members`/`user_roles` đều non-unique).
- Đề xuất: (1) Check duplicate bằng `storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull` (không phụ thuộc isActive); (2) thêm unique constraint DB `(user_id, store_id)` partial `WHERE deleted_at IS NULL` cho cả 2 bảng; (3) `getMembers` dùng `toMap(..., (a, b) -> a)`.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `ef50a96`: (1) check duplicate chuyển sang `findActiveStoreMember` (StoreMember, chỉ filter `deleted_at`); (3) `toMap` có merge function. Về (2): premise "không có unique constraint DB nào chặn" đã cũ tại thời điểm fix — `ux_store_members_user_store` và `ux_user_roles` đã được thêm vào V1 ở đợt fix 01; đợt này bổ sung `ux_user_roles_user_store (user_id, store_id) WHERE store_id IS NOT NULL AND deleted_at IS NULL` (V8) chặn nốt trường hợp 2 role khác nhau (MANAGER+STAFF) cùng store.

### [MEDIUM] Race condition check-then-act trên mọi plan limit — vượt giới hạn gói khi request đồng thời
- Vị trí: `SubscriptionLimitService.java:32-85` kết hợp `StoreService.java:56` (store), `StoreService.java:141` (staff), `ProductService.java:115,252`, `WarehouseService.java:48`, `OrderService.java:71`
- Mô tả: Mẫu chung là `COUNT(...) >= max → throw`, sau đó INSERT trong cùng transaction nhưng **không có lock/serialization**: 2 request `createStore` song song của business FREE (max 1, hiện có 0) đều đếm được 0 → cả hai insert thành công → 2 stores trên gói FREE. Không có constraint DB nào backstop số lượng. `checkXxxLimit` còn được khai báo `@Transactional(readOnly = true)` nhưng luôn được gọi bên trong transaction ghi của caller (join transaction hiện có, `readOnly` bị bỏ qua) — không gây lỗi nhưng gây hiểu nhầm ranh giới transaction.
- Tác động: Giới hạn gói (điểm bán hàng chính của subscription) có thể vượt bằng request đồng thời; với import product hàng loạt (`ProductService.java:252`) khả năng xảy ra cao hơn.
- Đề xuất: Lock row subscription trước khi đếm (`SELECT ... FOR UPDATE` qua `@Lock(PESSIMISTIC_WRITE)` trên `findByBusinessId` dùng riêng cho check limit) — mọi insert cùng business sẽ serialize qua row này. Mức độ chặt chẽ này đủ cho quy mô hiện tại, không cần advisory lock.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `4429f02`: 4 check (store/staff/product/warehouse) dùng `findByBusinessIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) + `propagation = MANDATORY` (thay `readOnly = true` gây hiểu nhầm — lock phải giữ tới lúc caller commit). `checkOrderLimit` không lock (hot path) và sau đó đã bị xóa hẳn ở `53239af`.

### [MEDIUM] requestUpgrade race → nhiều invoice PENDING; invoice PENDING không bao giờ hết hạn, period tính từ lúc request
- Vị trí: `SubscriptionService.java:111-134` (check pending rồi insert, không lock/unique); `SubscriptionService.java:117-120` (`periodStart = now` tại thời điểm request); `SubscriptionService.java:225-245` (confirm dùng `invoice.getPeriodEnd()` cũ)
- Mô tả: (1) Check `findPendingByBusinessId` rồi mới insert — 2 request song song đều tạo được invoice PENDING (check-then-act, không có unique constraint `(business_id, status=PENDING)`); admin có thể confirm cả hai. (2) `periodStart/periodEnd` chốt tại thời điểm **request**, nhưng subscription chỉ kích hoạt khi admin **confirm** — nếu admin confirm sau N ngày, user mất N ngày sử dụng (`expiresAt = periodEnd` cũ). (3) Invoice PENDING không có TTL: một invoice tạo từ nhiều tháng trước (giá cũ) vẫn confirm được.
- Tác động: Trạng thái invoice khó kiểm soát, user bị thiệt thời gian sử dụng đã trả tiền, có thể kích hoạt plan bằng invoice giá cũ.
- Đề xuất: Partial unique index `(business_id) WHERE status = 'PENDING'`; khi confirm, tính lại `expiresAt = confirmedAt + 30/365 ngày` (và cập nhật `periodStart/periodEnd` của invoice); thêm job/điều kiện auto-cancel invoice PENDING quá X ngày.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `4429f02`: (1) V9 `ux_subscription_invoices_pending (business_id) WHERE status = 'PENDING'`; (2) `confirmInvoice` tính lại `periodStart/periodEnd` từ thời điểm confirm, `expiresAt = periodEnd` mới; (3) scheduler hàng ngày auto-chuyển invoice PENDING quá hạn (mặc định 7 ngày, `subscription.invoice.pending-ttl-days`) sang FAILED kèm adminNote.

### [MEDIUM] Admin changePlan không reset expiresAt / pendingPlan / billingCycle — tạo trạng thái mâu thuẫn
- Vị trí: `SubscriptionService.java:190-203`
- Mô tả: `changePlan` set plan/status/limits/`startedAt` nhưng: (1) **không đụng `expiresAt`** — nếu admin hạ business về FREE mà `expiresAt` cũ còn trong quá khứ/tương lai, scheduler `expireOverdue` sau đó sẽ chuyển sub FREE thành EXPIRED (FREE theo thiết kế "không giới hạn thời gian" — `FEATURES.md:60`); ngược lại nếu admin nâng lên PRO, `expiresAt` null → PRO không bao giờ hết hạn (miễn phí vĩnh viễn). (2) Không clear `pendingPlan/pendingBillingCycle` — pending downgrade cũ vẫn được scheduler áp dụng đè lên quyết định của admin. (3) Không set `billingCycle` và `maxOrdersPerMonth`.
- Tác động: Công cụ điều chỉnh thủ công của admin dễ đưa subscription vào trạng thái sai mà không ai phát hiện cho tới khi scheduler chạy.
- Đề xuất: Trong `changePlan`: nếu FREE → `expiresAt = null`, `billingCycle = null`; nếu paid → yêu cầu truyền billingCycle và tính `expiresAt`; luôn clear `pendingPlan/pendingBillingCycle`.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `375c76c`: đúng theo đề xuất — FREE reset `expiresAt/billingCycle` về null; gói trả phí bắt buộc `billingCycle` (thêm vào `ChangePlanRequest`) và tính `expiresAt = now + 30/365 ngày`; luôn clear `pendingPlan/pendingBillingCycle`. Về `maxOrdersPerMonth`: đã xóa hẳn ở `53239af` (xem mục dưới).

### [MEDIUM] maxOrdersPerMonth không bao giờ được gán — checkOrderLimit là dead code, mọi gói unlimited đơn hàng
- Vị trí: `Subscription.java:54` (field), `SubscriptionLimitService.java:73-85` (check), `PlanLimits.java` (không có trường maxOrders); các điểm gán limit: `BusinessService.java:203-214`, `SubscriptionService.java:190-203,236-245`, `SubscriptionExpiryScheduler.java:44-48`, `BusinessSeeder.java`
- Mô tả: `OrderService.java:71` gọi `checkOrderLimit` mỗi lần tạo order, nhưng **không nơi nào trong codebase set `maxOrdersPerMonth`** (không có trong `PlanLimits`, không set khi tạo FREE sub, confirm invoice, changePlan, downgrade hay seeder) → luôn `null` → check luôn return sớm. Mỗi order tốn thêm 2 query vô ích (`findBusinessIdByStoreId` + `findByBusinessId`).
- Tác động: Nếu ý định thiết kế là giới hạn đơn/tháng cho FREE thì limit này chưa từng hoạt động; nếu không có ý định đó thì đây là dead code làm chậm hot path tạo order. `FEATURES.md:59-61` chỉ liệt kê 4 limit (store/staff/product/warehouse) — nghiêng về khả năng dead code.
- Đề xuất: Quyết định 1 trong 2: thêm `maxOrdersPerMonth` vào `PlanLimits` và gán ở mọi điểm chuyển plan, hoặc xóa field + `checkOrderLimit` + lời gọi trong `OrderService`.
- **Trạng thái (2026-07-10): ĐÃ FIX (xóa)** — `53239af`: xóa field `maxOrdersPerMonth` (entity + cột V1), `checkOrderLimit`, lời gọi trong `OrderService.create` và query orphan `countActiveByBusinessIdAndPeriod`. Lý do chọn xóa: FEATURES.md chỉ định nghĩa 4 limit, và check nằm trên hot path tạo order.

### [MEDIUM] Evict cache được gọi TRƯỚC khi transaction commit — trái với chính ghi chú trong code/docs, có cửa sổ re-cache dữ liệu cũ
- Vị trí: `StoreService.java:162,186,211` (evict bên trong method `@Transactional`); Javadoc `StoreAccessEvaluator.java:99-101` và `STORE_MEMBER_LIFECYCLE.md:183-185` yêu cầu "evict phải gọi sau khi transaction đã commit"
- Mô tả: `evictStoreRoleCache` được gọi ở cuối thân method nhưng **commit chỉ xảy ra sau khi method return** (proxy `@Transactional` bọc ngoài). Docs còn khẳng định sai: "Trong Spring, @Transactional commit khi method return — evict ở cuối method là đúng thứ tự". Thực tế thứ tự là: evict → commit. Request khác chen giữa evict và commit sẽ cache-miss, đọc DB thấy role CŨ (chưa commit) và ghi lại vào Redis → member vừa bị remove vẫn giữ quyền tới hết TTL 300s, đúng cái mà cơ chế evict muốn tránh.
- Tác động: Cửa sổ race nhỏ nhưng vô hiệu hóa mục đích của evict trong đúng kịch bản nhạy cảm nhất (đuổi nhân viên ngay lập tức). Đồng thời docs mô tả sai ngữ nghĩa transaction, dễ khiến dev sau này lặp lại pattern sai.
- Đề xuất: Dùng `TransactionSynchronization.afterCommit` (đăng ký qua `TransactionSynchronizationManager`) hoặc tách evict ra caller/`@TransactionalEventListener(phase = AFTER_COMMIT)`. Sửa lại đoạn giải thích trong `STORE_MEMBER_LIFECYCLE.md`.
- **Trạng thái (2026-07-10): ĐÃ FIX (đợt 02)** — `f1f7e23`: `StoreService` đăng ký evict qua `TransactionSynchronizationManager.registerSynchronization(afterCommit)` (`evictMemberCachesAfterCommit`). Đoạn giải thích sai trong `STORE_MEMBER_LIFECYCLE.md` mục 5 đã viết lại ở `ef50a96`.

### [MEDIUM] Docs yêu cầu evict business:member ở add/update/removeMember nhưng code không gọi (đối chiếu phần 02)
- Vị trí: `STORE_MEMBER_LIFECYCLE.md:111-117,138-143,172-181` (bảng evict: cả 3 thao tác đều "Có" cho `business:member`) vs `StoreService.java:162,186,211` (chỉ evict `store:role`)
- Mô tả: Phần 02 đã ghi nhận `evictBusinessRoleCache`/`evictBusinessMemberCache` không có caller nào (member bị gỡ vẫn truy cập catalog business tới 300s). Điểm bổ sung của phần này: đây còn là **sai lệch trực tiếp docs-vs-code** — `STORE_MEMBER_LIFECYCLE.md` mô tả evict `business:member` là "bắt buộc" trong cả 3 luồng, tức tài liệu mô tả một hành vi chưa từng được implement.
- Tác động: Xem phần 02 (HIGH). Về mặt tài liệu: dev đọc docs sẽ tin rằng cache đã được invalidate đúng.
- Đề xuất: Fix theo phần 02; sau khi fix, cập nhật docs nếu luồng thực tế khác mô tả.
- **Trạng thái (2026-07-10): ĐÃ FIX (đợt 02)** — `f1f7e23`: cả 3 luồng add/update/removeMember đều evict `store:role` **và** `business:member` sau commit — code nay khớp mô tả "bắt buộc" trong `STORE_MEMBER_LIFECYCLE.md`; docs mục 3b/3c/3d cập nhật kèm prefix `(afterCommit)` ở `ef50a96`.

### [MEDIUM] Deactivate business/store (isActive=false) không có hiệu lực — không nơi nào kiểm tra isActive
- Vị trí: `BusinessService.java:185-190` (`setBusinessStatus`), `StoreService.java:97-102` (`setStoreStatus`); `BusinessAccessEvaluator`/`StoreAccessEvaluator` (không đọc `business.isActive`/`store.isActive`); `StoreService.java:214-217` (`findStoreOrThrow` dùng `findById` thuần)
- Mô tả: Endpoint set status tồn tại và ghi DB, nhưng không evaluator, service hay query nào trong module này chặn thao tác trên business/store có `isActive = false`. Member của store bị deactivate vẫn đọc/ghi mọi thứ như thường; business bị SUPER_ADMIN khóa (`isActive=false`) vẫn hoạt động đầy đủ. Tương tự, `findStoreOrThrow` không filter `deletedAt` trong khi các query list (`findByBusinessIdAndDeletedAtIsNull`) có filter — hiện chưa có API xóa store nên chưa lộ, nhưng nếu sau này set `deletedAt` cho store thì mọi endpoint theo `storeId` vẫn truy cập được store đã xóa.
- Tác động: "Khóa business/cửa hàng" chỉ là cosmetic — không dùng được cho nghiệp vụ đình chỉ tenant vi phạm hoặc tạm đóng cửa hàng. Cột `deletedAt` của Business/Store hiện là soft-delete infra mồ côi (không có API xóa, không cascade nào được định nghĩa).
- Đề xuất: Quyết định ngữ nghĩa của `isActive`: nếu là chặn truy cập → thêm check trong evaluator (kèm cache) hoặc ở service; nếu chỉ là hiển thị → ghi rõ vào docs. Đổi `findStoreOrThrow` sang query có `deletedAtIsNull` để nhất quán trước khi có API xóa.
- **Trạng thái (2026-07-10): ĐÃ XỬ LÝ (docs-only)** — quyết định: `isActive` hiện là cờ hiển thị, chưa enforce — ghi rõ trong `FEATURES.md` mục 3 (`53239af`); enforce trong evaluator để lại khi có yêu cầu nghiệp vụ đình chỉ tenant thật. `findStoreOrThrow` đã chuyển sang `findByIdAndDeletedAtIsNull` (`de4c8d4`).

### [LOW] Add/UpdateMemberRequest chấp nhận mọi RoleName — có thể gán ROLE_OWNER/ROLE_SUPER_ADMIN ở store level
- Vị trí: `AddMemberRequest.java:9`, `UpdateMemberRequest.java:8` (`@NotNull RoleName role` — không giới hạn); `StoreService.java:155,178`
- Mô tả: OWNER có thể gán role `ROLE_SUPER_ADMIN`, `ROLE_SUPPORT`, `ROLE_OWNER` cho member ở store level. Không leo thang được quyền global (authorities lấy từ role global `business/store IS NULL`) hay business OWNER (`findActiveBusinessRole` yêu cầu `store IS NULL`), nhưng tạo bản ghi `user_roles` sai ngữ nghĩa: `hasAccess` chỉ nhận MANAGER/STAFF nên user gán "ROLE_OWNER store-level" thực tế mất sạch quyền trong store — trạng thái rác gây nhầm lẫn.
- Tác động: Không phải lỗ hổng leo thang nhưng là data-integrity gap; docs (`RoleName.java` comment, `STORE_MEMBER_LIFECYCLE.md`) quy định store-scoped chỉ có MANAGER/STAFF.
- Đề xuất: Validate `role ∈ {ROLE_MANAGER, ROLE_STAFF}` trong `addMember`/`updateMember` (throw `IllegalArgumentException`).
- **Trạng thái (2026-07-10): ĐÃ FIX** — `ef50a96`: `StoreService.validateStoreMemberRole` chặn mọi role ngoài MANAGER/STAFF ở cả `addMember` và `updateMember`.

### [LOW] POST /api/businesses/default không idempotent — retry tạo business + store + warehouse trùng lặp
- Vị trí: `BusinessController.java:39-44`, `BusinessService.java:92-140`
- Mô tả: Endpoint dành cho luồng đăng ký nhưng không có guard "user đã có business default chưa" — client retry (mất mạng, double-tap) tạo thêm bộ business/store/warehouse/subscription mới mỗi lần gọi. `POST /api/businesses` cũng không giới hạn số business/user (mỗi cái kèm 1 sub FREE) — chấp nhận được nếu multi-business là thiết kế, nhưng endpoint `default` thì nên idempotent.
- Tác động: Rác dữ liệu, user mobile dễ rơi vào trạng thái nhiều "Doanh nghiệp của tôi" trùng tên.
- Đề xuất: Nếu user đã là OWNER của ít nhất 1 business → trả về business hiện có (200) thay vì tạo mới, hoặc chặn bằng lỗi 409.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `de4c8d4`: `createDefaultBusiness` idempotent — user đã là OWNER thì trả về business hiện có kèm store/warehouse đầu tiên (có thể null). `POST /api/businesses` thường vẫn không giới hạn số business/user (multi-business là thiết kế).

### [LOW] buildFreeSubscription hardcode limit thay vì dùng PlanLimits — nguy cơ drift
- Vị trí: `BusinessService.java:203-214` (hardcode `1, 0, 50, 1`) vs `PlanLimits.java` (`FREE(1, 0, 50, 1)`); `BusinessSeeder.java:71-75` dùng `PlanLimits` đúng cách
- Mô tả: Hai nguồn chân lý cho limit FREE. Hiện giá trị khớp nhau, nhưng sửa `PlanLimits.FREE` sau này sẽ không ảnh hưởng business tạo qua `BusinessService`.
- Tác động: Rủi ro bảo trì, chưa gây lỗi runtime.
- Đề xuất: `buildFreeSubscription` đọc từ `PlanLimits.FREE` như seeder.
- **Trạng thái (2026-07-10): ĐÃ FIX** — `de4c8d4`: `buildFreeSubscription` đọc 4 limit từ `PlanLimits.FREE`, hết 2 nguồn chân lý.

### [LOW] Các vấn đề nhỏ khác (subscription/docs)
- Vị trí: `SubscriptionService.java:77` / `SubscriptionExpiryScheduler.java:79-92` / `StoreService.java:53-56` / `STORE_MEMBER_LIFECYCLE.md:162` / `LIFECYCLE.md:1`
- Mô tả:
  1. `scheduleDowngrade` chỉ set `pendingPlan`, không set `pendingBillingCycle` — field này không bao giờ có giá trị (response luôn null); nếu downgrade về gói trả phí thấp hơn thì không biết chu kỳ nào (hiện scheduler chuyển thẳng EXPIRED nên chưa lộ).
  2. Email cảnh báo hết hạn gửi **lặp lại mỗi ngày** trong suốt 7 ngày cuối (`findExpiringSoon` không có cờ đã-gửi) — spam 7 email/subscription.
  3. `checkStaffLimit` đếm cả member `isActive=false` (chỉ filter `deletedAt`) — member bị tạm khóa vẫn chiếm quota; chấp nhận được nhưng nên ghi rõ trong docs.
  4. `STORE_MEMBER_LIFECYCLE.md:162` tham chiếu `BUSINESS_MEMBER_LIFECYCLE.md` — file không tồn tại trong `api/docs/`.
  5. `LIFECYCLE.md` tiêu đề "hệ thống OmniFlow" — tên sản phẩm cũ/khác, không khớp QuikTech POS.
  6. `BusinessMember` được ghi khi tạo business nhưng không nơi nào trong module dùng cho authorization (chỉ `UserRole`) — hai bảng có thể lệch nhau mà không ai phát hiện (dead-ish data path, đúng rule "mention, don't delete").
- Tác động: Nhỏ, chủ yếu chất lượng dữ liệu/docs/UX email.
- Đề xuất: Sửa lần lượt: set/loại bỏ `pendingBillingCycle`; thêm cờ `expiryWarningSentAt`; cập nhật docs; tạo hoặc bỏ tham chiếu `BUSINESS_MEMBER_LIFECYCLE.md`; thống nhất tên sản phẩm.
- **Trạng thái (2026-07-10): ĐÃ XỬ LÝ** —
  1. `pendingBillingCycle`: giữ field, thêm comment trong `scheduleDowngrade` giải thích vì sao không set (downgrade về gói trả phí hiện chuyển EXPIRED, user chọn chu kỳ khi re-subscribe) — `de4c8d4`.
  2. Email spam: thêm cột `expiry_warning_sent_at` (V10) + filter trong `findExpiringSoon`, reset khi kích hoạt chu kỳ mới — `de4c8d4`.
  3. Staff quota đếm cả inactive: ghi rõ trong `FEATURES.md` mục 5 — `de4c8d4`.
  4. Tham chiếu `BUSINESS_MEMBER_LIFECYCLE.md` chết: đã thay bằng mô tả inline trong `STORE_MEMBER_LIFECYCLE.md` — `ef50a96`.
  5. Tên "OmniFlow" trong `LIFECYCLE.md`: đổi thành QuikTech POS — `de4c8d4`.
  6. `BusinessMember` dead-ish: **chưa xử lý** (đúng rule "mention, don't delete") — cần quyết định riêng: dùng cho authorization hay bỏ bảng.

## 4. Điểm tốt

- Tách bạch rõ `StoreMember` (metadata) / `UserRole` (authorization) đúng như docs; mọi thao tác membership update cả hai trong cùng `@Transactional`.
- `getInvoiceForBusiness` kiểm tra invoice thuộc đúng business (trả 404 thay vì 403 — không lộ tồn tại invoice); toàn bộ endpoint subscription của owner đều scoped theo `businessId` + `@businessAccess.isOwner`.
- Admin API subscription gom về `/api/admin/subscriptions` với `@PreAuthorize("hasRole('SUPER_ADMIN')")` class-level, `changePlan` còn có thêm `@PreAuthorize` ở service (defense in depth).
- Plan limit được gọi đủ tại cả 5 điểm tạo resource; count query đều filter `deleted_at IS NULL` (store/product/warehouse/member) — soft-deleted không chiếm quota.
- Scheduler expiry xử lý riêng nhánh pending-downgrade (reset limit theo gói đích, FREE giữ ACTIVE) và bulk-expire bằng 1 UPDATE; email gửi `@Async` nên lỗi SMTP không rollback transaction.
- `getStores`/`getBusinesses` gộp quyền OWNER + store-membership bằng `LinkedHashMap` chống trùng, dùng JOIN FETCH tránh N+1.
