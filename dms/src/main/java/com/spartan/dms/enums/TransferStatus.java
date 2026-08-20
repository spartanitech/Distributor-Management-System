package com.spartan.dms.enums;

/**
 * Lifecycle of a StockTransfer. Transfers are applied atomically
 * (deduct source, add destination) at creation time in the current
 * design, so COMPLETED is the terminal state for a normal transfer;
 * CANCELLED is used when a transfer is reversed.
 */
public enum TransferStatus {
    COMPLETED,
    CANCELLED
}
