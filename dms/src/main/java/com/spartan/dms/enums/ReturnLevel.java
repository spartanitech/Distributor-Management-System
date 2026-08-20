package com.spartan.dms.enums;

/**
 * Which leg of the Company -> Super Stockist -> Distributor -> Shop chain a
 * return travels back up.
 *
 * A single physical return movement is BOTH a "Purchase Return" to the
 * party sending the goods back and a "Sales Return Received" to the party
 * receiving them. It is therefore stored as ONE PurchaseReturn row, read
 * from two different directions, rather than duplicated into two records:
 *
 *   DISTRIBUTOR_TO_SUPER_STOCKIST
 *     - Distributor's view : Purchase Return (their stock decreases)
 *     - Super Stockist view: Sales Return Received (their stock increases)
 *
 *   SUPER_STOCKIST_TO_COMPANY
 *     - Super Stockist view: Purchase Return (their stock decreases)
 *     - Admin/Company view : Sales Return Received (company stock increases)
 *
 * The bottom leg (Shop -> Distributor) has no PurchaseReturn row at all:
 * a Shop is not a stock-holding party in this system and has no login, so
 * that movement is recorded by SalesReturn instead, which also settles the
 * shop's invoice balance.
 */
public enum ReturnLevel {
    DISTRIBUTOR_TO_SUPER_STOCKIST,
    SUPER_STOCKIST_TO_COMPANY
}
