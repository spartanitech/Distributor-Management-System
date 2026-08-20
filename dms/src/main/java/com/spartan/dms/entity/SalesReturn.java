package com.spartan.dms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A Shop returning goods (fully or partially) against a specific
 * DISTRIBUTOR_TO_SHOP invoice line item. Only ever accepted on the 25th
 * of the month — SalesReturnService.createReturn() rejects any other
 * day — so returnDate on every row here is always some month's 25th.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sales_returns", indexes = {
        @Index(name = "idx_sr_return_date", columnList = "return_date"),
        @Index(name = "idx_sr_distributor", columnList = "distributor_id"),
        @Index(name = "idx_sr_shop", columnList = "shop_id"),
        @Index(name = "idx_sr_invoice", columnList = "invoice_id"),
})
public class SalesReturn extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

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

    @Column(name = "reason", length = 500)
    private String reason;

    // PENDING until the 1-25 settlement cycle it falls in is generated
    // (viewed) at least once, then SETTLED — purely informational, does
    // not block creating further returns.
    @Builder.Default
    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING";
}
