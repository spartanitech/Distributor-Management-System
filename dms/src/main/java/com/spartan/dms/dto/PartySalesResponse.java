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
public class PartySalesResponse {

    private Long partyId;
    private String partyName;

    // SUPER_STOCKIST / DISTRIBUTOR / SHOP — tells the frontend what the
    // next drill-down click on this row should pass back.
    private String partyType;

    private Long invoiceCount;
    private BigDecimal totalSales;
}
