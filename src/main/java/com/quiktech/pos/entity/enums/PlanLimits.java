package com.quiktech.pos.entity.enums;

public enum PlanLimits {
    FREE(1, 0, 50, 1),
    BASIC(2, 20, 200, 20),
    PRO(3, null, null, null);

    public final Integer maxStores;
    public final Integer maxStaff;
    public final Integer maxProducts;
    public final Integer maxWarehouses;

    PlanLimits(Integer maxStores, Integer maxStaff, Integer maxProducts, Integer maxWarehouses) {
        this.maxStores = maxStores;
        this.maxStaff = maxStaff;
        this.maxProducts = maxProducts;
        this.maxWarehouses = maxWarehouses;
    }
}
