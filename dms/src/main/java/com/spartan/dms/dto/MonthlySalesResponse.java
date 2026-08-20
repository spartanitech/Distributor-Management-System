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
public class MonthlySalesResponse {

    private String month;

    private Integer year;

    private BigDecimal totalSales;

    private Long totalOrders;
}
