package com.quiktech.backend.entity.enums;

public enum RoleName {
    ROLE_SUPER_ADMIN, ROLE_SUPPORT,    // global (store_id IS NULL)
    ROLE_OWNER, ROLE_MANAGER, ROLE_STAFF    // store-scoped (store_id IS NOT NULL)
}
