package com.spartan.dms.enums;

/**
 * Which leg of the Company -> Super Stockist -> Distributor -> Shop chain
 * an Invoice represents. Every invoice, at every level, is stored as a
 * row in the same `invoices` table so Admin can see the complete paper
 * trail in one place; which FK columns are populated depends on this:
 *
 *   COMPANY_TO_SUPER_STOCKIST   -> superStockist set, distributor/shop null
 *   SUPER_STOCKIST_TO_DISTRIBUTOR -> superStockist + distributor set, shop null
 *   DISTRIBUTOR_TO_SHOP         -> distributor + shop set, superStockist null
 */
public enum InvoiceLevel {
    COMPANY_TO_SUPER_STOCKIST,
    SUPER_STOCKIST_TO_DISTRIBUTOR,
    DISTRIBUTOR_TO_SHOP
}
