package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportResponse {

    private String reportName;

    private LocalDate fromDate;

    private LocalDate toDate;

    private Long totalInvoices;

    private Long totalOrders;

    private Long totalProducts;

    private Long totalShops;

    private Long totalDistributors;

    private BigDecimal totalSales;

    private BigDecimal totalPaidAmount;

    private BigDecimal totalPendingAmount;

    private BigDecimal totalPartialPaidAmount;

    private String generatedBy;

    private LocalDate generatedDate;

    private String exportType;
}