-- ============================================================================
-- V10: CHECK payment_method cho orders / purchase_orders
--
-- orders.payment_method và purchase_orders.payment_method trước đây là String tự do
-- (không CHECK), lệch với enum PaymentMethod và với payments.chk_payments_method.
-- Backstop DB: chỉ chấp nhận đúng 6 giá trị của enum PaymentMethod. Validate ở
-- boundary (DTO nhận enum) đã trả 400 cho input sai; CHECK này là lớp phòng thủ cuối.
-- ============================================================================

ALTER TABLE orders ADD CONSTRAINT chk_orders_payment_method
    CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'CREDIT_CARD', 'DEBIT_CARD', 'MOBILE_PAYMENT', 'OTHER'));

ALTER TABLE purchase_orders ADD CONSTRAINT chk_po_payment_method
    CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'CREDIT_CARD', 'DEBIT_CARD', 'MOBILE_PAYMENT', 'OTHER'));
