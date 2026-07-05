package com.quiktech.backend.dto.response.payment;

import java.math.BigDecimal;
import java.util.List;

public record PaymentPageResult(
    List<PaymentResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    BigDecimal totalIncome,
    BigDecimal totalExpense
) {}
