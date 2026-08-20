package com.spartan.dms.entity;

import com.spartan.dms.enums.LedgerTransactionType;
import com.spartan.dms.enums.OwnerType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable, append-only audit trail of every stock movement for every
 * product, at whichever location it happened (Company / Super Stockist /
 * Distributor / Shop). This is the single source of truth for "what
 * happened to this product's stock and when" — every other module
 * (Invoice, SalesReturn, ProductRequest/StockTransfer fulfillment,
 * Product stock-entry endpoints) writes one row here per stock-affecting
 * action instead of the UI/user ever inserting one directly.
 *
 * There is deliberately no update/delete path anywhere in the codebase
 * for this entity — see ProductLedgerService, which only ever appends.
 * A correction to stock is itself a new ledger row (MANUAL_STOCK_CORRECTION,
 * STOCK_ADJUSTMENT, DAMAGE_LOSS, ...), never an edit of history.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_ledger", indexes = {
        @Index(name = "idx_pl_product", columnList = "product_id"),
        @Index(name = "idx_pl_txn_date", columnList = "transaction_date_time"),
        @Index(name = "idx_pl_txn_type", columnList = "transaction_type"),
        @Index(name = "idx_pl_owner_type", columnList = "owner_type"),
        @Index(name = "idx_pl_super_stockist", columnList = "super_stockist_id"),
        @Index(name = "idx_pl_distributor", columnList = "distributor_id"),
        @Index(name = "idx_pl_shop", columnList = "shop_id"),
        @Index(name = "idx_pl_voucher_no", columnList = "voucher_no"),
})
public class ProductLedger extends BaseEntity {

    // When the underlying transaction actually happened (invoice date,
    // return date, transfer timestamp, etc.) — NOT necessarily the same
    // instant as BaseEntity.createdAt, though for most transaction types
    // they coincide.
    @Column(name = "transaction_date_time", nullable = false)
    private LocalDateTime transactionDateTime;

    // Human-readable reference back to the source document: invoice
    // number, "SR-{id}" for a sales return, "ST-{id}" for a transfer,
    // "ADJ-{id}" for an adjustment, etc.
    @Column(name = "voucher_no", length = 100)
    private String voucherNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private LedgerTransactionType transactionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // Free-text batch/lot number. Nullable — most products in this system
    // aren't currently batch-tracked, so this is populated only when the
    // caller has one to record.
    @Column(name = "batch_no", length = 100)
    private String batchNo;

    // Which node in the Company -> Super Stockist -> Distributor -> Shop
    // hierarchy this entry's quantities/balance belong to. Mirrors the
    // discriminator pattern already used by Warehouse/StockTransfer so a
    // single table can represent stock at any level without new tables.
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id")
    private SuperStockist superStockist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id")
    private Shop shop;

    @Column(name = "in_quantity", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal inQuantity = BigDecimal.ZERO;

    @Column(name = "out_quantity", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal outQuantity = BigDecimal.ZERO;

    // Running stock balance for this product AT THIS LOCATION,
    // immediately after this entry is applied.
    @Column(name = "balance_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal balanceQuantity;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    // abs(inQuantity - outQuantity) * unitCost — the monetary value of
    // this single movement, always non-negative.
    @Column(name = "stock_value", precision = 14, scale = 2)
    private BigDecimal stockValue;

    // Username of whoever performed the underlying action (or "SYSTEM"
    // for entries created outside a request context, e.g. data seeding).
    @Column(name = "performed_by", length = 100)
    private String performedBy;

    @Column(name = "remarks", length = 500)
    private String remarks;
}
