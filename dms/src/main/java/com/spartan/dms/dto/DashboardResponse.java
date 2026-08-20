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
public class DashboardResponse {

    // Dashboard Cards
    private Long totalUsers;
    private Long totalDistributors;
    private Long totalSuperStockists;
    private Long totalShops;
    private Long totalCategories;
    private Long totalProducts;
    private Long totalInvoices;
    private Long totalPayments;

    // Payment Summary
    private BigDecimal totalSales;
    private BigDecimal totalPaidAmount;
    private BigDecimal totalPendingAmount;
    private BigDecimal totalPartialPaidAmount;

    // Invoice Count
    private Long paidInvoices;
    private Long unpaidInvoices;
    private Long partiallyPaidInvoices;

    // Product Summary
    private Long lowStockProducts;
    private Long outOfStockProducts;

    // Today's Summary
    private Long todayOrders;
    private BigDecimal todaySales;

    // Monthly Summary
    private Long monthlyOrders;
    private BigDecimal monthlySales;

    // Yearly Summary
    private Long yearlyOrders;
    private BigDecimal yearlySales;

    // KPI Trend Indicators (current month vs previous month, % change)
    private Double revenueTrendPercent;
    private Double invoicesTrendPercent;
    private Double distributorsTrendPercent;
    private Double pendingTrendPercent;

    // Sales Returns Summary (this month)
    private Long monthlySalesReturns;
    private java.math.BigDecimal monthlySalesReturnAmount;
}