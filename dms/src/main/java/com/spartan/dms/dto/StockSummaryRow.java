package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockSummaryRow {

    private Long productId;
    private String productName;
    private String unit;

    private BigDecimal openingQty;
    private BigDecimal openingRate;
    private BigDecimal openingValue;

    private BigDecimal inwardQty;
    private BigDecimal inwardRate;
    private BigDecimal inwardValue;

    private BigDecimal outwardQty;
    private BigDecimal outwardRate;
    private BigDecimal outwardValue;

    private BigDecimal closingQty;
    private BigDecimal closingRate;
    private BigDecimal closingValue;
}
