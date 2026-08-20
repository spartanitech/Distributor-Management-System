package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.OutstandingDetailResponse;
import com.spartan.dms.dto.PagedResponse;
import com.spartan.dms.service.OutstandingReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/accounts/outstanding")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class OutstandingReportController {

    private final OutstandingReportService outstandingReportService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<OutstandingDetailResponse>>> getOutstanding(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String partyType,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false, defaultValue = "totalOutstanding") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(outstandingReportService.getOutstanding(
                search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir, page, size));
    }

    @GetMapping(value = "/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String partyType,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false, defaultValue = "totalOutstanding") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDir) throws IOException {

        byte[] excel = outstandingReportService.exportExcel(
                search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=outstanding.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    @GetMapping(value = "/export/csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String partyType,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false, defaultValue = "totalOutstanding") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDir) {

        byte[] csv = outstandingReportService.exportCsv(
                search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=outstanding.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String partyType,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false, defaultValue = "totalOutstanding") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDir) {

        byte[] pdf = outstandingReportService.exportPdf(
                search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=outstanding.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
