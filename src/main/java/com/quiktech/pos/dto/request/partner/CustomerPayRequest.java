package com.quiktech.pos.dto.request.partner;

import com.quiktech.pos.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CustomerPayRequest(
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    // Enum thay vì String tự do — giá trị lạ bị Jackson từ chối ngay (400),
    // null → mặc định CASH (giữ tương thích client cũ)
    PaymentMethod paymentMethod,
    // Store gắn phiếu thu công nợ; null → fallback store đầu tiên của business (kèm log warn)
    Long storeId
) {}
