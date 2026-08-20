package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.StockSummaryResponse;
import com.spartan.dms.service.StockSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/stock-summary")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class StockSummaryController {

    private final StockSummaryService stockSummaryService;

    @GetMapping
    public ResponseEntity<ApiResponse<StockSummaryResponse>> getStockSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String search) {

        return ResponseEntity.ok(stockSummaryService.getStockSummary(fromDate, toDate, categoryId, productId, search));
    }

    @GetMapping(value = "/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String search) throws IOException {

        byte[] excel = stockSummaryService.exportExcel(fromDate, toDate, categoryId, productId, search);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=stock-summary.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String search) {

        byte[] pdf = stockSummaryService.exportPdf(fromDate, toDate, categoryId, productId, search);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=stock-summary.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
