package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.SalesReturnRequest;
import com.spartan.dms.dto.SalesReturnResponse;
import com.spartan.dms.service.SalesReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sales-returns")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SalesReturnController {

    private final SalesReturnService salesReturnService;

    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @PostMapping
    public ApiResponse<SalesReturnResponse> createReturn(@Valid @RequestBody SalesReturnRequest request) {
        return salesReturnService.createReturn(request);
    }

    @GetMapping
    public ApiResponse<List<SalesReturnResponse>> getReturns() {
        return salesReturnService.getReturns();
    }

    @GetMapping("/{id}")
    public ApiResponse<SalesReturnResponse> getReturnById(@PathVariable Long id) {
        return salesReturnService.getReturnById(id);
    }

    @GetMapping("/settlement")
    public ApiResponse<Map<String, Object>> getMonthlySettlement(
            @RequestParam int year,
            @RequestParam int month) {
        return salesReturnService.getMonthlySettlement(year, month);
    }

    // Sales return register PDF (Shop -> Distributor), scoped in the
    // service to the caller's own visible rows.
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportSalesReturnsPdf() {

        byte[] pdf = salesReturnService.exportSalesReturnsPdf();

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=sales-returns.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
