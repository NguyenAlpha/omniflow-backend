package com.quiktech.backend.entity.enums;

/**
 * Enum để định nghĩa các trạng thái của đơn hàng.
 *
 * PENDING: Đơn hàng vừa được tạo, chưa hoàn tất.
 * COMPLETED: Đơn hàng đã hoàn tất (ghi nhận nợ khách).
 * CANCELLED: Đơn hàng bị hủy (hoàn inventory, không tính nợ).
 */
public enum OrderStatus {
    PENDING("PENDING", "Chờ xử lý"),
    COMPLETED("COMPLETED", "Hoàn tất"),
    CANCELLED("CANCELLED", "Hủy bỏ");

    private final String code;
    private final String label;

    OrderStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Chuyển đổi string sang OrderStatus.
     * @param code giá trị string (ví dụ: "PENDING", "COMPLETED", "CANCELLED")
     * @return OrderStatus tương ứng hoặc null nếu không tìm thấy
     */
    public static OrderStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (OrderStatus status : OrderStatus.values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return this.code;
    }
}

