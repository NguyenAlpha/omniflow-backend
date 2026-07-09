-- Backstop DB cho quy tắc "mỗi user tối đa 1 role trong 1 store".
--
-- ux_user_roles hiện có bao gồm role_id nên vẫn cho phép 1 user có đồng thời
-- MANAGER + STAFF trong cùng store (2 role_id khác nhau). Khi cả 2 cùng active,
-- findActiveStoreRole (Optional) trả 2 rows -> IncorrectResultSizeDataAccessException
-- ngay trong StoreAccessEvaluator -> mọi request của user đó vào store lỗi 500.
--
-- Không xét is_active: vòng đời member là 1 cặp StoreMember/UserRole duy nhất
-- (update đổi role trên row hiện có, remove soft-delete cả 2) — không bao giờ
-- có lý do hợp lệ để tồn tại 2 row user_roles cùng (user, store) chưa xóa.
CREATE UNIQUE INDEX ux_user_roles_user_store ON user_roles(user_id, store_id)
    WHERE store_id IS NOT NULL AND deleted_at IS NULL;
