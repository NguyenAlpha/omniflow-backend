-- Giải phóng username/email của user đã xóa mềm.
-- UNIQUE constraint thường tính cả row có deleted_at IS NOT NULL → email/username
-- "bị chiếm vĩnh viễn" bởi tài khoản đã xóa, người mới không đăng ký lại được.
-- Thay bằng partial unique index chỉ áp dụng cho user đang sống (deleted_at IS NULL) —
-- mọi lookup runtime đều đã filter deleted_at IS NULL (@SQLRestriction trên User entity)
-- nên không có nguy cơ trả về 2 user trùng định danh.

ALTER TABLE users DROP CONSTRAINT users_username_key;
ALTER TABLE users DROP CONSTRAINT users_email_key;

CREATE UNIQUE INDEX uq_users_username_active ON users (username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_email_active ON users (email) WHERE deleted_at IS NULL;
