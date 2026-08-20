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
@Table(name = "invoices", indexes = {
        @Index(name = "idx_invoice_date", columnList = "invoice_date"),
        @Index(name = "idx_invoice_distributor", columnList = "distributor_id"),
        @Index(name = "idx_invoice_super_stockist", columnList = "super_stockist_id"),
        @Index(name = "idx_invoice_shop", columnList = "shop_id"),
        @Index(name = "idx_invoice_level", columnList = "invoice_level"),
        @Index(name = "idx_invoice_payment_status", columnList = "payment_status"),
})
public class Invoice extends BaseEntity {

    // Optimistic-locking guard (BUG-C2 fix). Every write path that mutates
    // this invoice's financial fields (PaymentService create/update/delete/
    // reject, SalesReturnService) now goes through a read-modify-save cycle
    // inside a @Transactional method; without a version column, two
    // concurrent requests can both read the same paidAmount, both pass
    // their own validation, and the second save silently overwrites the
    // first (a lost update). With @Version, the second of two concurrent
    // saves against the same row throws ObjectOptimisticLockingFailureException
    // instead of silently succeeding — already mapped to a clean HTTP 409
    // by GlobalExceptionHandler.handleOptimisticLocking(), so no new error
    // handling needed. Hibernate manages this column automatically; no
    // existing code needs to read or set it.
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 50)
    private String invoiceNumber;

    // Which leg of Company -> Super Stockist -> Distributor -> Shop this
    // invoice represents. Defaults to the original DISTRIBUTOR_TO_SHOP
    // behavior so existing rows/callers are unaffected.
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_level", nullable = false, length = 40)
    @Builder.Default
    private com.spartan.dms.enums.InvoiceLevel invoiceLevel = com.spartan.dms.enums.InvoiceLevel.DISTRIBUTOR_TO_SHOP;

    // Issuer for SUPER_STOCKIST_TO_DISTRIBUTOR, recipient for
    // COMPANY_TO_SUPER_STOCKIST. Null for DISTRIBUTOR_TO_SHOP.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id")
    private SuperStockist superStockist;

    // Recipient for SUPER_STOCKIST_TO_DISTRIBUTOR, issuer for
    // DISTRIBUTOR_TO_SHOP. Null for COMPANY_TO_SUPER_STOCKIST.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    // Only populated for DISTRIBUTOR_TO_SHOP.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id")
    private Shop shop;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "sub_total", precision = 12, scale = 2)
    private BigDecimal subTotal;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "tax_amount", precision = 12, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "balance_amount", precision = 12, scale = 2)
    private BigDecimal balanceAmount;

    // Running total of every Sales Return recorded against this invoice —
    // reduces what the shop still owes the same way paidAmount does, so
    // balanceAmount = totalAmount - paidAmount - returnedAmount. Kept
    // separate from paidAmount so a return is never confused with cash
    // actually received.
    @Column(name = "returned_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal returnedAmount = BigDecimal.ZERO;

    @Column(name = "payment_status", length = 30)
    private String paymentStatus;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    @Column(name = "invoice_status", length = 30)
    private String invoiceStatus;

    @Column(name = "watermark", length = 50)
    private String watermark;

    @Column(name = "pdf_path")
    private String pdfPath;

    @Column(name = "qr_code")
    private String qrCode;

    @Column(name = "remarks", length = 500)
    private String remarks;

    // Who actually raised this invoice, as opposed to which parties it is
    // BETWEEN (superStockist/distributor/shop above). Needed because the
    // two differ: a COMPANY_TO_SUPER_STOCKIST invoice names a Super
    // Stockist as a party but is created by Admin, so party-based scoping
    // alone can't answer "invoices created by X". Populated on create and
    // never changed afterwards.
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_username", length = 100)
    private String createdByUsername;

    // ADMIN / SUPER_STOCKIST / DISTRIBUTOR — stored denormalised so
    // "created by a Super Stockist" stays answerable even if that user's
    // role is later changed or the account is removed.
    @Column(name = "created_by_role", length = 40)
    private String createdByRole;

    // ---- BUG-C1 / BUG-H1 / BUG-H2 / BUG-H3 fix: single authoritative formula ----
    //
    // Every place that used to hand-roll "balance = total - paid" (or forgot
    // to subtract returnedAmount, or forgot to recompute at all — reject/
    // update payment) now calls this one method instead. That's the "clean
    // common solution" for what were four separate but related bugs: the
    // formula, and what counts as PAID/PARTIALLY_PAID/UNPAID, can now only
    // be defined in one place, so PaymentService and SalesReturnService can
    // never again drift into disagreeing about it.
    //
    // Callers are responsible for setting totalAmount / paidAmount /
    // returnedAmount themselves first (this never trusts anything the
    // client sent directly — every caller in this codebase only ever
    // arrives at those inputs via server-computed values). Null total /
    // paid / returned are treated as zero so this is always safe to call.
    public void recalculateBalanceAndStatus() {
        java.math.BigDecimal total = this.totalAmount != null ? this.totalAmount : BigDecimal.ZERO;
        java.math.BigDecimal paid = this.paidAmount != null ? this.paidAmount : BigDecimal.ZERO;
        java.math.BigDecimal returned = this.returnedAmount != null ? this.returnedAmount : BigDecimal.ZERO;

        this.balanceAmount = total.subtract(paid).subtract(returned);

        if (this.balanceAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.paymentStatus = "PAID";
        } else if (paid.compareTo(BigDecimal.ZERO) > 0 || returned.compareTo(BigDecimal.ZERO) > 0) {
            // Matches the string set already accepted by
            // InvoiceRepository.sumPartialPaidAmount()/countPartiallyPaidInvoices()
            // ('PARTIAL', 'PARTIALLY_PAID', 'PARTIALLY PAID').
            this.paymentStatus = "PARTIALLY_PAID";
        } else {
            // Matches InvoiceRepository.countUnpaidInvoices() ('UNPAID') —
            // the old code here used to write "PENDING", which that query
            // never matched, so the Dashboard's "unpaid invoices" count was
            // silently always undercounting. Fixed as part of the same
            // change since it's the same root cause (inconsistent status
            // strings written by hand in multiple places).
            this.paymentStatus = "UNPAID";
        }
    }
}