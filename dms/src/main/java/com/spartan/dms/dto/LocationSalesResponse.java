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
public class LocationSalesResponse {

    private String state;
    private String district;
    private Long distributorId;
    private String distributorName;
    private Long invoiceCount;
    private BigDecimal totalSales;
    private BigDecimal totalPaid;
    private BigDecimal totalPending;
}
