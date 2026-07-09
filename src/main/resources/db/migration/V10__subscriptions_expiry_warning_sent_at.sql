-- Cờ chống spam email cảnh báo sắp hết hạn: scheduler chạy hàng ngày và
-- findExpiringSoon quét cửa sổ 7 ngày — không có cờ này, mỗi subscription nhận
-- 7 email lặp lại trong 7 ngày cuối chu kỳ.
-- NULL = chưa gửi cho chu kỳ hiện tại; reset về NULL khi kích hoạt chu kỳ mới.
ALTER TABLE subscriptions ADD COLUMN expiry_warning_sent_at TIMESTAMPTZ;
