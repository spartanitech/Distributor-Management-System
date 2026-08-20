package com.spartan.dms.entity;

import com.spartan.dms.enums.ReturnLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One upward return movement of goods between two stock-holding parties.
 *
 * IMPORTANT: this single row represents BOTH sides of the transaction --
 * a Purchase Return for the party sending goods back, and a Sales Return
 * Received for the party receiving them (see ReturnLevel). It is
 * deliberately NOT duplicated into two records, so the two views can never
 * drift out of sync and a single return can never be double-counted in
 * stock or reports.
 *
 * Which FK columns are populated depends on returnLevel:
 *   DISTRIBUTOR_TO_SUPER_STOCKIST -> distributor + superStockist set
 *   SUPER_STOCKIST_TO_COMPANY     -> superStockist set, distributor null
 *                                    (Company is the root and has no entity)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "purchase_returns", indexes = {
        @Index(name = "idx_pret_return_date", columnList = "return_date"),
        @Index(name = "idx_pret_level", columnList = "return_level"),
        @Index(name = "idx_pret_distributor", columnList = "distributor_id"),
        @Index(name = "idx_pret_super_stockist", columnList = "super_stockist_id"),
        @Index(name = "idx_pret_product", columnList = "product_id"),
})
public class PurchaseReturn extends BaseEntity {

    // Human-readable voucher reference, e.g. "PR-12". Assigned after save
    // (needs the generated id) and used as the Product Ledger voucherNo so
    // a ledger row can be traced back to this exact return.
    @Column(name = "return_number", unique = true, length = 50)
    private String returnNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_level", nullable = false, length = 40)
    private ReturnLevel returnLevel;

    // The Distributor sending goods back. Set only for
    // DISTRIBUTOR_TO_SUPER_STOCKIST; null when a Super Stockist returns to
    // Company.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    // Set for BOTH levels: the receiving party for
    // DISTRIBUTOR_TO_SUPER_STOCKIST, the sending party for
    // SUPER_STOCKIST_TO_COMPANY.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id")
    private SuperStockist superStockist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "return_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal returnAmount;

    @Column(name = "return_date", nullable = false)
    private LocalDate returnDate;

    // Structured reason -- what Reports/Analytics group by.
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 30)
    private com.spartan.dms.enums.ReturnReason reasonCode;

    // Free text the user typed. Required when reasonCode is OTHERS,
    // optional extra detail otherwise.
    @Column(name = "reason_note", length = 500)
    private String reasonNote;

    // Human-readable resolved reason ("Damaged", or the custom note for
    // OTHERS). Kept so existing history views/exports that already read
    // `reason` keep working unchanged.
    @Column(name = "reason", length = 500)
    private String reason;

    // Username of whoever recorded it, for the Admin-facing return history.
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /* ---------- Approval workflow ----------
       A return is a REQUEST until the receiving party approves it. Stock,
       warehouses and ledgers are untouched while PENDING; every effect is
       applied once, on approval. Never default this to APPROVED -- that
       would let a submission move inventory with nobody signing off. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private com.spartan.dms.enums.ReturnStatus status = com.spartan.dms.enums.ReturnStatus.PENDING;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "decided_at")
    private java.time.LocalDateTime decidedAt;

    // Why a return was turned down, shown back to the submitter.
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
}
