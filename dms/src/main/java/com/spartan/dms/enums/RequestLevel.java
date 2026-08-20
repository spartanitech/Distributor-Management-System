package com.spartan.dms.enums;

/**
 * Which leg of the stock-request chain a ProductRequest represents:
 *   DISTRIBUTOR_TO_SUPER_STOCKIST -> a Distributor asking their own Super
 *     Stockist for stock. Approved/fulfilled by that Super Stockist (or
 *     Admin), drawn from the Super Stockist's own Warehouse row.
 *   SUPER_STOCKIST_TO_COMPANY -> a Super Stockist asking Admin/Company for
 *     stock (typically raised when their own warehouse can't cover a
 *     distributor request). Approved/fulfilled by Admin only, drawn from
 *     Product.stockQuantity (the Company root).
 */
public enum RequestLevel {
    DISTRIBUTOR_TO_SUPER_STOCKIST,
    SUPER_STOCKIST_TO_COMPANY
}
