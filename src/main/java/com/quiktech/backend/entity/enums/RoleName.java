package com.quiktech.backend.entity.enums;

public enum RoleName {
    ROLE_SUPER_ADMIN, ROLE_SUPPORT,    // global (business_id IS NULL, store_id IS NULL)
    ROLE_OWNER,    // business-scoped (business_id IS NOT NULL, store_id IS NULL)
    ROLE_BUSINESS_MANAGER,    // business-scoped (business_id IS NOT NULL, store_id IS NULL) — trợ lý: quản mọi store, không đụng billing/business admin
    ROLE_MANAGER, ROLE_STAFF    // store-scoped (store_id IS NOT NULL)
}
