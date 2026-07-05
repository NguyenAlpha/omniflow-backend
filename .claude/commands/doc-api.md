# /doc-api

Viết file tài liệu Markdown cho một API controller theo chuẩn của dự án.

## Cách dùng

```
/doc-api <ControllerName>
```

Ví dụ: `/doc-api BusinessController`

---

## Quy trình thực hiện

1. Đọc file controller: `src/main/java/com/quiktech/backend/controller/<ControllerName>.java`
2. Đọc tất cả DTO liên quan:
   - `dto/request/**` — các request class được dùng trong controller
   - `dto/response/**` — các response class được trả về
   - Bao gồm cả các class `common` nếu có dùng (ví dụ `SetStatusRequest`)
3. Viết file `docs/api/<RESOURCE>.md` theo đúng format bên dưới.

---

## Format chuẩn

Theo sát style của `docs/api/AUTH.md`:

- Ngôn ngữ: **tiếng Việt**
- Ghi rõ endpoint nào **yêu cầu JWT**, endpoint nào **public**
- Với endpoint yêu cầu quyền cụ thể (`@PreAuthorize`), ghi rõ role được phép
- Mỗi endpoint gồm đủ: path params, request body (bảng field + ràng buộc), response mẫu JSON, bảng lỗi
- Các DTO dùng chung (response object) đặt phần mô tả ở đầu file, không lặp lại ở từng endpoint
- HTTP status: dùng đúng code trả về trong code (201 Created, 200 OK, v.v.)
- Tên file output: `docs/api/<TÊN_RESOURCE_VIẾT_HOA>.md`
