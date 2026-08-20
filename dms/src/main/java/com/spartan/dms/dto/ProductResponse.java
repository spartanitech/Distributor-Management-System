package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    private Long id;

    private String productName;

    private String productCode;

    private String barcode;

    private Long categoryId;
    private String categoryName;

    private String brandName;

    private String unit;

    private Integer stockQuantity;

    private Integer minimumStock;

    private BigDecimal purchasePrice;

    private BigDecimal ssPrice;

    private BigDecimal distributorPrice;

    private BigDecimal sellingPrice;

    private BigDecimal mrp;

    private BigDecimal gstPercentage;

    private String description;

    private Boolean active;

    private String productImage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}