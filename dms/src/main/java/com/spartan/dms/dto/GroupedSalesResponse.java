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
public class GroupedSalesResponse {
    private String label;        // district/state name, distributor name, or SS name
    private Long groupId;         // distributorId / superStockistId when applicable (null for district/state) — used for click-to-filter
    private BigDecimal totalSales;
    private Long totalOrders;
}
