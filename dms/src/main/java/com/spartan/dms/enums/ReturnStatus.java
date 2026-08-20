package com.spartan.dms.enums;

/**
 * Approval state of a PurchaseReturn.
 *
 * A return has NO effect on stock, warehouses, ledgers, dashboards or
 * reports while PENDING -- it is only a request. All of those effects are
 * applied exactly once, at the moment it is APPROVED
 * (PurchaseReturnService.applyReturnEffects), so a rejected or
 * still-pending return can never move inventory.
 */
public enum ReturnStatus {
    PENDING,
    APPROVED,
    REJECTED
}
