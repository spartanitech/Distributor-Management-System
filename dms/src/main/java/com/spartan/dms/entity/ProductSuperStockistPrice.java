package com.spartan.dms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Lets Admin set a DIFFERENT ssPrice for the same product per individual
 * Super Stockist (e.g. SS-A gets Product X at ₹50, SS-B at ₹55), instead
 * of one fixed price for every Super Stockist. When no row exists here for
 * a given (product, superStockist) pair, InvoiceService falls back to
 * Product.ssPrice as the default rate.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_super_stockist_prices",
       uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "super_stockist_id"}))
public class ProductSuperStockistPrice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id", nullable = false)
    private SuperStockist superStockist;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;
}
