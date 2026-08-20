package com.spartan.dms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(name = "product_name", nullable = false, length = 150)
    private String productName;

    @Column(name = "product_code", nullable = false, unique = true, length = 50)
    private String productCode;

    @Column(name = "barcode", unique = true, length = 100)
    private String barcode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "brand_name", length = 100)
    private String brandName;

    @Column(name = "unit", length = 30)
    private String unit;

    @Column(name = "stock_quantity")
    private Integer stockQuantity;

    @Column(name = "minimum_stock")
    private Integer minimumStock;

    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    // Tier 1: what Company charges a Super Stockist per unit
    // (COMPANY_TO_SUPER_STOCKIST invoices). Admin-only visibility.
    @Column(name = "ss_price", precision = 12, scale = 2)
    private BigDecimal ssPrice;

    // Tier 2: what a Super Stockist charges a Distributor per unit
    // (SUPER_STOCKIST_TO_DISTRIBUTOR invoices) — the "DP". Visible to
    // Admin and to the Super Stockist themselves (it's their own selling
    // price); hidden from Distributor/Shop.
    @Column(name = "distributor_price", precision = 12, scale = 2)
    private BigDecimal distributorPrice;

    // Tier 3: what a Distributor charges a Shop per unit
    // (DISTRIBUTOR_TO_SHOP invoices) — the final "SP". Visible to Admin,
    // Super Stockist and the Distributor (their own selling price).
    @Column(name = "selling_price", precision = 12, scale = 2)
    private BigDecimal sellingPrice;

    @Column(name = "mrp", precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(name = "gst_percentage", precision = 5, scale = 2)
    private BigDecimal gstPercentage;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "product_image")
    private String productImage;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}