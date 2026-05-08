package com.omniflow.backend.entity.enums;

public enum RoleName {
    SUPER_ADMIN, SUPPORT,    // global (store_id IS NULL)
    OWNER, MANAGER, STAFF    // store-scoped (store_id IS NOT NULL)
}
