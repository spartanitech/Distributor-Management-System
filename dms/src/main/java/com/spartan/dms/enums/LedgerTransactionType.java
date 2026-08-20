package com.spartan.dms.enums;

/**
 * Every kind of event the Product Ledger can record. One enum shared by
 * every module that touches stock so the ledger has a single, consistent
 * vocabulary — nothing writes a free-text "type" string.
 *
 * PURCHASE exists now so the ledger schema doesn't need to change the day
 * the Purchase module ships; nothing currently writes it.
 */
public enum LedgerTransactionType {
    OPENING_STOCK,
    PURCHASE,
    SALES,
    SALES_RETURN,
    PURCHASE_RETURN,
    STOCK_TRANSFER_IN,
    STOCK_TRANSFER_OUT,
    STOCK_ADJUSTMENT,
    DAMAGE_LOSS,
    MANUAL_STOCK_CORRECTION
}
