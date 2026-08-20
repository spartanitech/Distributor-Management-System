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
public class SalesReturnSettlementRow {
    private Long distributorId;
    private String distributorName;
    private Integer returnCount;
    private Integer totalQuantity;
    private BigDecimal totalReturnAmount;
}
