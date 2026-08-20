package com.spartan.dms.enums;

/**
 * Why goods were sent back. A fixed set rather than free text so returns
 * can actually be reported/grouped on (Reports and Sales Analysis can
 * count "how much came back damaged this month"), which a free-text field
 * makes impossible.
 *
 * OTHERS is the escape hatch: PurchaseReturn.reasonNote carries the
 * user-typed explanation, and is REQUIRED only for OTHERS (see
 * PurchaseReturnService.resolveReason).
 */
public enum ReturnReason {
    DAMAGED,
    EXPIRED,
    WRONG_ITEM,
    OTHERS
}
