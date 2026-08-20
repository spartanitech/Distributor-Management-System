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
public class LowStockResponse {

    private Long productId;

    private String productName;

    private String productCode;

    private String barcode;

    private String categoryName;

    private String unit;

    private Integer currentStock;

    private Integer minimumStock;

    private BigDecimal purchasePrice;

    private BigDecimal sellingPrice;

    private Boolean active;

    private LocalDateTime updatedAt;
}
