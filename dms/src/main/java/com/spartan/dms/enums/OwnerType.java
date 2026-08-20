package com.spartan.dms.enums;

/**
 * Who a Warehouse stock row or one end of a StockTransfer belongs to.
 * COMPANY has no linked entity (it's the root of the hierarchy and its
 * stock is tracked directly on Product.stockQuantity); SUPER_STOCKIST and
 * DISTRIBUTOR rows carry a FK to the corresponding entity.
 */
public enum OwnerType {
    COMPANY,
    SUPER_STOCKIST,
    DISTRIBUTOR
}
