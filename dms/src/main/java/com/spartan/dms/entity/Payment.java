package com.spartan.dms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_date", columnList = "payment_date"),
        @Index(name = "idx_payment_invoice", columnList = "invoice_id"),
        @Index(name = "idx_payment_distributor", columnList = "distributor_id"),
        @Index(name = "idx_payment_shop", columnList = "shop_id"),
        @Index(name = "idx_payment_super_stockist", columnList = "super_stockist_id"),
})
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    // Set for DISTRIBUTOR_TO_SHOP (the paying distributor) and for
    // SUPER_STOCKIST_TO_DISTRIBUTOR (the paying distributor). Null for
    // COMPANY_TO_SUPER_STOCKIST, where the payer is a Super Stockist, not
    // a distributor. See PaymentService.createPayment() for the
    // per-invoice-level validation that keeps these three FKs consistent
    // with invoice.invoiceLevel.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    // Set only for DISTRIBUTOR_TO_SHOP. Null for the two upstream levels,
    // which have no Shop involved at all.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id")
    private Shop shop;

    // Set for COMPANY_TO_SUPER_STOCKIST (the paying Super Stockist) and for
    // SUPER_STOCKIST_TO_DISTRIBUTOR (the receiving Super Stockist — kept
    // here too so a Super Stockist's own payment history/outstanding can
    // be queried directly without joining back through Distributor). Null
    // for DISTRIBUTOR_TO_SHOP.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id")
    private SuperStockist superStockist;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    @Column(name = "payment_status", nullable = false, length = 30)
    private String paymentStatus;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "proof_image")
    private String proofImage;

    // File name of the most recently uploaded proof (kept in sync by
    // PaymentProofService alongside proofImage) so the Payments table can
    // show "File Name, View Icon, Delete Icon" without an extra lookup per
    // row -- proofImage alone is just a URL, not human-readable.
    @Column(name = "proof_file_name")
    private String proofFileName;

    @Column(name = "verified")
    @Builder.Default
    private Boolean verified = false;

    @Column(name = "verified_by", length = 100)
    private String verifiedBy;

    @Column(name = "remarks", length = 500)
    private String remarks;
}