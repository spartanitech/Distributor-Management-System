package com.spartan.dms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Lets Admin set a DIFFERENT distributorPrice (DP) for the same product per
 * individual Distributor, instead of one fixed DP for every Distributor.
 * When no row exists here for a given (product, distributor) pair,
 * InvoiceService falls back to Product.distributorPrice as the default rate.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_distributor_prices",
       uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "distributor_id"}))
public class ProductDistributorPrice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;
}
