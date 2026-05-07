package com.omniflow.backend.dto.response.common;

public enum ErrorCode {
    // Auth
    USERNAME_TAKEN,
    EMAIL_TAKEN,
    INVALID_CREDENTIALS,

    // User
    USER_NOT_FOUND,

    // Store
    STORE_NOT_FOUND,
    STORE_MEMBER_NOT_FOUND,
    STORE_MEMBER_ALREADY_EXISTS,

    // Product / Catalog
    PRODUCT_NOT_FOUND,
    PRODUCT_SKU_TAKEN,
    CATEGORY_NOT_FOUND,
    CATEGORY_NAME_TAKEN,
    UNIT_NOT_FOUND,
    UNIT_NAME_TAKEN,

    // Warehouse / Inventory
    WAREHOUSE_NOT_FOUND,
    INVENTORY_NOT_FOUND,
    INSUFFICIENT_STOCK,

    // Order
    ORDER_NOT_FOUND,
    ORDER_ALREADY_COMPLETED,
    ORDER_ALREADY_CANCELLED,

    // Purchase Order
    PURCHASE_ORDER_NOT_FOUND,

    // Return Order
    RETURN_ORDER_NOT_FOUND,

    // Customer / Supplier
    CUSTOMER_NOT_FOUND,
    CUSTOMER_CODE_TAKEN,
    SUPPLIER_NOT_FOUND,
    SUPPLIER_CODE_TAKEN,

    // Payment
    PAYMENT_NOT_FOUND,

    // Validation
    VALIDATION_ERROR,

    // Generic
    FORBIDDEN,
    INTERNAL_ERROR
}
