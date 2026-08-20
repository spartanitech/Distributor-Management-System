package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.DashboardResponse;
import com.spartan.dms.dto.LowStockResponse;
import com.spartan.dms.dto.PaymentSummaryResponse;
import com.spartan.dms.dto.RecentInvoiceResponse;
import com.spartan.dms.dto.SalesSummaryResponse;
import com.spartan.dms.dto.TopDistributorResponse;
import com.spartan.dms.dto.TopProductResponse;
import com.spartan.dms.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {

        return ResponseEntity.ok(
                dashboardService.getDashboard()
        );
    }

    // Outstanding KPI drill-down: who owes money, district, products bought,
    // and total pending amount per shop/distributor/super stockist.
    @GetMapping("/outstanding-details")
    public ResponseEntity<ApiResponse<List<com.spartan.dms.dto.OutstandingDetailResponse>>> getOutstandingDetails() {

        return ResponseEntity.ok(
                dashboardService.getOutstandingDetails()
        );
    }

    @GetMapping("/recent-invoices")
    public ResponseEntity<ApiResponse<List<RecentInvoiceResponse>>> getRecentInvoices() {

        return ResponseEntity.ok(
                dashboardService.getRecentInvoices()
        );
    }

    @GetMapping("/low-stock")
    public ResponseEntity<ApiResponse<List<LowStockResponse>>> getLowStockProducts() {

        return ResponseEntity.ok(
                dashboardService.getLowStockProducts()
        );
    }

    @GetMapping("/sales-summary")
    public ResponseEntity<ApiResponse<SalesSummaryResponse>> getSalesSummary(
            @RequestParam(name = "months", defaultValue = "6") int months) {

        return ResponseEntity.ok(
                dashboardService.getSalesSummary(months)
        );
    }

    @GetMapping("/payment-summary")
    public ResponseEntity<ApiResponse<PaymentSummaryResponse>> getPaymentSummary() {

        return ResponseEntity.ok(
                dashboardService.getPaymentSummary()
        );
    }

    @GetMapping("/top-products")
    public ResponseEntity<ApiResponse<List<TopProductResponse>>> getTopProducts(
            @RequestParam(name = "limit", defaultValue = "5") int limit) {

        return ResponseEntity.ok(
                dashboardService.getTopProducts(limit)
        );
    }

    @GetMapping("/top-distributors")
    public ResponseEntity<ApiResponse<List<TopDistributorResponse>>> getTopDistributors(
            @RequestParam(name = "limit", defaultValue = "5") int limit) {

        return ResponseEntity.ok(
                dashboardService.getTopDistributors(limit)
        );
    }

    /**
     * Backs the "Sales by District/State/Distributor/Super Stockist" bar
     * charts. groupBy = district | state | distributor | superStockist.
     * All other params are optional filters that AND together, and also
     * double as the payload the frontend re-sends when a chart bar is
     * clicked (e.g. clicking "Madurai" re-calls this with district=Madurai).
     */
    @GetMapping("/sales-grouped")
    public ResponseEntity<ApiResponse<List<com.spartan.dms.dto.GroupedSalesResponse>>> getSalesGrouped(
            @RequestParam(name = "groupBy", defaultValue = "district") String groupBy,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Long distributorId,
            @RequestParam(required = false) Long superStockistId,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dateTo) {

        return ResponseEntity.ok(
                dashboardService.getSalesGrouped(groupBy, district, state, distributorId, superStockistId, month, year, dateFrom, dateTo)
        );
    }
}
