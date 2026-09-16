package com.quiktech.pos.entity.enums;

/**
 * Quy ước dấu của {@code InventoryTransaction.quantity} theo từng type:
 * <ul>
 *   <li>{@code IN}, {@code OUT}: luôn dương — hướng suy từ type
 *       (OUT hiểu ngầm là trừ kho).</li>
 *   <li>{@code TRANSFER}: delta có dấu — chân xuất âm, chân nhập dương
 *       (mỗi lần transfer ghi 2 bản ghi).</li>
 *   <li>{@code ADJUSTMENT}: delta có dấu — điều chỉnh tăng dương, giảm âm.</li>
 * </ul>
 * DB constraint tương ứng: {@code chk_inv_tx_qty CHECK (quantity <> 0)}.
 * Khi aggregate báo cáo, KHÔNG được SUM(quantity) trộn lẫn các type.
 */
public enum InventoryTransactionType {
    IN,
    OUT,
    TRANSFER,
    ADJUSTMENT
}
