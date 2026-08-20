package com.spartan.dms.entity;

import com.spartan.dms.enums.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per (owner, product) = current on-hand quantity. This is the
 * single stock ledger used for both Super Stockist warehouses and
 * Distributor stock, so the same code path (StockTransferService) can
 * move stock across any number of Super Stockists / Distributors without
 * new tables or hardcoded owners. Company-level stock is NOT stored here
 * — it stays on Product.stockQuantity, since Company is the single root
 * of the hierarchy and already has a home for it.
 *
 * ownerType discriminates which FK is populated:
 *   SUPER_STOCKIST -> superStockist set, distributor null
 *   DISTRIBUTOR    -> distributor set, superStockist null
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "warehouses",
        uniqueConstraints = {
                // One stock row per owner+product combination, regardless
                // of how many super stockists/distributors exist.
                @UniqueConstraint(
                        name = "uk_warehouse_super_stockist_product",
                        columnNames = {"super_stockist_id", "product_id"}),
                @UniqueConstraint(
                        name = "uk_warehouse_distributor_product",
                        columnNames = {"distributor_id", "product_id"})
        }
)
public class Warehouse extends BaseEntity {

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
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 0;
}
