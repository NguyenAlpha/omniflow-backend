-- Flyway Migration V2: Subscription Invoice — Bank Transfer Support
-- Adds fields for manual bank transfer confirmation flow:
--   bank_transfer_ref : mã/nội dung chuyển khoản do business owner gửi lên
--   confirmed_by      : admin user đã xác nhận thanh toán
--   admin_note        : ghi chú của admin khi confirm/reject
--   confirmed_at      : thời điểm admin xác nhận

ALTER TABLE subscription_invoices
    ADD COLUMN bank_transfer_ref VARCHAR(100),
    ADD COLUMN confirmed_by      BIGINT,
    ADD COLUMN admin_note        TEXT,
    ADD COLUMN confirmed_at      TIMESTAMPTZ;

ALTER TABLE subscription_invoices
    ADD CONSTRAINT fk_sub_invoices_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users(id);

CREATE INDEX idx_sub_invoices_confirmed_by ON subscription_invoices(confirmed_by) WHERE confirmed_by IS NOT NULL;
