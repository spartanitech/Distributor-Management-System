package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.LocationSalesResponse;
import com.spartan.dms.dto.ReportResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.ShopRepository;
import com.spartan.dms.security.SecurityUtils;
import com.spartan.dms.util.ExcelGenerator;
import com.spartan.dms.util.PdfGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final InvoiceRepository invoiceRepository;
    private final ProductRepository productRepository;
    private final DistributorRepository distributorRepository;
    private final ShopRepository shopRepository;
    private final SecurityUtils securityUtils;
    private final ExcelGenerator excelGenerator;
    private final PdfGenerator pdfGenerator;

    private List<Invoice> scopedInvoices(String fromDate, String toDate) {

        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);

        List<Invoice> base;
        if (securityUtils.isSuperStockist()) {
            base = invoiceRepository.findByDistributorSuperStockistId(securityUtils.getScopedSuperStockistId());
        } else {
            Long scopedDistributorId = securityUtils.getScopedDistributorId();
            base = (scopedDistributorId != null)
                    ? invoiceRepository.findByDistributorId(scopedDistributorId)
                    : invoiceRepository.findAll();
        }

        return base.stream()
                .filter(i -> from == null || (i.getInvoiceDate() != null && !i.getInvoiceDate().isBefore(from)))
                .filter(i -> to == null || (i.getInvoiceDate() != null && !i.getInvoiceDate().isAfter(to)))
                .collect(Collectors.toList());
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDate.parse(s);
    }

    private ReportResponse buildReport(List<Invoice> invoices, String name, String fromDate, String toDate) {

        BigDecimal totalSales = invoices.stream()
                .map(i -> i.getTotalAmount() != null ? i.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPaid = invoices.stream()
                .map(i -> i.getPaidAmount() != null ? i.getPaidAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPending = invoices.stream()
                .map(i -> i.getBalanceAmount() != null ? i.getBalanceAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPartial = invoices.stream()
                .filter(i -> i.getPaymentStatus() != null && i.getPaymentStatus().toUpperCase().contains("PARTIAL"))
                .map(i -> i.getBalanceAmount() != null ? i.getBalanceAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Long totalDistributorsForReport;
        if (securityUtils.isSuperStockist()) {
            totalDistributorsForReport = distributorRepository.countBySuperStockistId(securityUtils.getScopedSuperStockistId());
        } else {
            Long scopedDistributorId = securityUtils.getScopedDistributorId();
            totalDistributorsForReport = scopedDistributorId != null ? 1L : distributorRepository.count();
        }

        return ReportResponse.builder()
                .reportName(name)
                .fromDate(parseDate(fromDate))
                .toDate(parseDate(toDate))
                .totalInvoices((long) invoices.size())
                .totalProducts(productRepository.count())
                .totalDistributors(totalDistributorsForReport)
                .totalShops(shopRepository.count())
                .totalSales(totalSales)
                .totalPaidAmount(totalPaid)
                .totalPendingAmount(totalPending)
                .totalPartialPaidAmount(totalPartial)
                .generatedDate(LocalDate.now())
                .build();
    }

    public ApiResponse<ReportResponse> getSalesReport(String fromDate, String toDate) {

        return ApiResponse.<ReportResponse>builder()
                .success(true)
                .message("Sales Report Generated Successfully")
                .data(buildReport(scopedInvoices(fromDate, toDate), "Sales Report", fromDate, toDate))
                .build();
    }

    public ApiResponse<ReportResponse> getPaymentReport(String fromDate, String toDate) {

        return ApiResponse.<ReportResponse>builder()
                .success(true)
                .message("Payment Report Generated Successfully")
                .data(buildReport(scopedInvoices(fromDate, toDate), "Payment Report", fromDate, toDate))
                .build();
    }

    public ApiResponse<ReportResponse> getInvoiceReport(String fromDate, String toDate) {

        return ApiResponse.<ReportResponse>builder()
                .success(true)
                .message("Invoice Report Generated Successfully")
                .data(buildReport(scopedInvoices(fromDate, toDate), "Invoice Report", fromDate, toDate))
                .build();
    }

    public ApiResponse<ReportResponse> getProductReport() {

        return ApiResponse.<ReportResponse>builder()
                .success(true)
                .message("Product Report Generated Successfully")
                .data(buildReport(scopedInvoices(null, null), "Product Report", null, null))
                .build();
    }

    public ApiResponse<ReportResponse> getDistributorReport() {

        if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Only an admin can view the cross-distributor report");
        }

        return ApiResponse.<ReportResponse>builder()
                .success(true)
                .message("Distributor Report Generated Successfully")
                .data(buildReport(scopedInvoices(null, null), "Distributor Report", null, null))
                .build();
    }

    public ApiResponse<ReportResponse> getShopReport() {

        return ApiResponse.<ReportResponse>builder()
                .success(true)
                .message("Shop Report Generated Successfully")
                .data(buildReport(scopedInvoices(null, null), "Shop Report", null, null))
                .build();
    }

    public ApiResponse<ReportResponse> getDashboardReport() {

        if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Only an admin can view the dashboard report");
        }

        return ApiResponse.<ReportResponse>builder()
                .success(true)
                .message("Dashboard Report Generated Successfully")
                .data(buildReport(scopedInvoices(null, null), "Dashboard Report", null, null))
                .build();
    }

    public ApiResponse<List<LocationSalesResponse>> getSalesByLocation(
            String state, String district, Long distributorId, String fromDate, String toDate) {

        if (distributorId != null) {
            securityUtils.assertDistributorAccess(distributorId);
        }

        List<Invoice> invoices = scopedInvoices(fromDate, toDate);

        Map<String, List<Invoice>> grouped = new LinkedHashMap<>();

        for (Invoice invoice : invoices) {
            Distributor d = invoice.getDistributor();
            if (d == null) {
                continue;
            }
            if (state != null && !state.equalsIgnoreCase(nullToEmpty(d.getState()))) {
                continue;
            }
            if (district != null && !district.equalsIgnoreCase(nullToEmpty(d.getDistrict()))) {
                continue;
            }
            if (distributorId != null && !distributorId.equals(d.getId())) {
                continue;
            }
            String key = nullToEmpty(d.getState()) + "||" + nullToEmpty(d.getDistrict()) + "||" + d.getId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(invoice);
        }

        List<LocationSalesResponse> result = new ArrayList<>();

        for (Map.Entry<String, List<Invoice>> entry : grouped.entrySet()) {
            List<Invoice> group = entry.getValue();
            Distributor d = group.get(0).getDistributor();

            BigDecimal totalSales = group.stream()
                    .map(i -> i.getTotalAmount() != null ? i.getTotalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalPaid = group.stream()
                    .map(i -> i.getPaidAmount() != null ? i.getPaidAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalPending = group.stream()
                    .map(i -> i.getBalanceAmount() != null ? i.getBalanceAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(LocationSalesResponse.builder()
                    .state(d.getState())
                    .district(d.getDistrict())
                    .distributorId(d.getId())
                    .distributorName(d.getDistributorName())
                    .invoiceCount((long) group.size())
                    .totalSales(totalSales)
                    .totalPaid(totalPaid)
                    .totalPending(totalPending)
                    .build());
        }

        result.sort(Comparator.comparing(LocationSalesResponse::getTotalSales).reversed());

        return ApiResponse.<List<LocationSalesResponse>>builder()
                .success(true)
                .message("Sales By Location Generated Successfully")
                .data(result)
                .build();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Turns a ReportResponse (the same aggregate data getSalesReport() etc.
     * already compute) into "Metric / Value" rows for the generic table
     * exporters below.
     */
    private List<String[]> reportRows(ReportResponse report) {

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Total Invoices", String.valueOf(report.getTotalInvoices())});
        rows.add(new String[]{"Total Products", String.valueOf(report.getTotalProducts())});
        rows.add(new String[]{"Total Distributors", String.valueOf(report.getTotalDistributors())});
        rows.add(new String[]{"Total Shops", String.valueOf(report.getTotalShops())});
        rows.add(new String[]{"Total Sales", String.valueOf(report.getTotalSales())});
        rows.add(new String[]{"Total Paid Amount", String.valueOf(report.getTotalPaidAmount())});
        rows.add(new String[]{"Total Pending Amount", String.valueOf(report.getTotalPendingAmount())});
        rows.add(new String[]{"Total Partial Paid Amount", String.valueOf(report.getTotalPartialPaidAmount())});
        return rows;
    }

    // BUG-H9 fix: this used to return a hardcoded success message with no
    // file at all. It now reuses the same sales-report data getSalesReport()
    // already computes and feeds it through the same generateReportTablePdf()
    // used by the other real report exports in this codebase (e.g.
    // OutstandingReportService).
    public byte[] exportPdfReport(String fromDate, String toDate) {

        ReportResponse report = buildReport(scopedInvoices(fromDate, toDate), "Sales Report", fromDate, toDate);

        String subtitle = "Generated: " + report.getGeneratedDate()
                + (fromDate != null || toDate != null
                    ? "  |  Period: " + (fromDate == null ? "-" : fromDate) + " to " + (toDate == null ? "-" : toDate)
                    : "");

        return pdfGenerator.generateReportTablePdf(
                "Sales Report",
                subtitle,
                null, null,
                new String[]{"Metric", "Value"},
                reportRows(report),
                null);
    }

    public byte[] exportSalesByLocationExcel(String state, String district, Long distributorId,
                                              String fromDate, String toDate) {

        ApiResponse<List<LocationSalesResponse>> report =
                getSalesByLocation(state, district, distributorId, fromDate, toDate);

        String[] headers = {"State", "District", "Distributor", "Invoice Count", "Total Sales", "Total Paid", "Total Pending"};

        List<String[]> rows = report.getData().stream()
                .map(r -> new String[]{
                        nullToEmpty(r.getState()),
                        nullToEmpty(r.getDistrict()),
                        nullToEmpty(r.getDistributorName()),
                        String.valueOf(r.getInvoiceCount()),
                        String.valueOf(r.getTotalSales()),
                        String.valueOf(r.getTotalPaid()),
                        String.valueOf(r.getTotalPending())
                })
                .collect(Collectors.toList());

        try {
            return excelGenerator.generateGenericExcel("Sales By Location", headers, rows);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel report", e);
        }
    }

    // BUG-H9 fix: same as exportPdfReport() above -- was a hardcoded
    // success message with no file generated. Now reuses the same
    // generateGenericExcel() helper exportSalesByLocationExcel() already
    // uses correctly.
    public byte[] exportExcelReport(String fromDate, String toDate) {

        ReportResponse report = buildReport(scopedInvoices(fromDate, toDate), "Sales Report", fromDate, toDate);

        String[] headers = {"Metric", "Value"};
        List<String[]> rows = reportRows(report);

        try {
            return excelGenerator.generateGenericExcel("Sales Report", headers, rows);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel report", e);
        }
    }
}
