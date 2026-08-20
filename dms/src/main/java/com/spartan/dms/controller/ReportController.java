package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.LocationSalesResponse;
import com.spartan.dms.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/sales")
    public ResponseEntity<ApiResponse<?>> getSalesReport(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        return ResponseEntity.ok(reportService.getSalesReport(fromDate, toDate));
    }

    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<?>> getPaymentReport(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        return ResponseEntity.ok(reportService.getPaymentReport(fromDate, toDate));
    }

    @GetMapping("/invoices")
    public ResponseEntity<ApiResponse<?>> getInvoiceReport(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        return ResponseEntity.ok(reportService.getInvoiceReport(fromDate, toDate));
    }

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<?>> getProductReport() {

        return ResponseEntity.ok(reportService.getProductReport());
    }

    // Cross-distributor totals; admin only (enforced again in the service).
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/distributors")
    public ResponseEntity<ApiResponse<?>> getDistributorReport() {

        return ResponseEntity.ok(reportService.getDistributorReport());
    }

    @GetMapping("/shops")
    public ResponseEntity<ApiResponse<?>> getShopReport() {

        return ResponseEntity.ok(reportService.getShopReport());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<?>> getDashboardReport() {

        return ResponseEntity.ok(reportService.getDashboardReport());
    }

    /**
     * The categorized report: sales broken down by state / district /
     * distributor. Admin can view/filter across everyone; a distributor
     * login only ever gets rows for their own distributor (enforced in
     * ReportService regardless of the filters passed here).
     */
    @GetMapping("/sales-by-location")
    public ResponseEntity<ApiResponse<List<LocationSalesResponse>>> getSalesByLocation(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) Long distributorId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        return ResponseEntity.ok(
                reportService.getSalesByLocation(state, district, distributorId, fromDate, toDate));
    }

    @GetMapping(value = "/sales-by-location/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportSalesByLocationExcel(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) Long distributorId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        byte[] excel = reportService.exportSalesByLocationExcel(state, district, distributorId, fromDate, toDate);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=sales-by-location.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    // BUG-H9 fix: used to return ApiResponse<String> with a hardcoded
    // "exported successfully" message and no actual file. Now returns a
    // real generated PDF, same pattern as exportSalesByLocationExcel above.
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdfReport(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        byte[] pdf = reportService.exportPdfReport(fromDate, toDate);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=sales-report.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // BUG-H9 fix: same as exportPdfReport() above -- now returns a real
    // generated Excel file instead of a hardcoded success message.
    @GetMapping(value = "/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcelReport(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        byte[] excel = reportService.exportExcelReport(fromDate, toDate);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=sales-report.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }
}
