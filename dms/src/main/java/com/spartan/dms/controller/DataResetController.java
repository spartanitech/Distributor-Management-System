package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.service.DataResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DataResetController {

    private final DataResetService dataResetService;

    // Deliberately a POST with no path parameters and no soft-delete/undo --
    // this is a one-time "clear setup/test data before going live" action,
    // gated to ADMIN only, confirmed twice on the frontend before it's
    // ever called (see script.js). Leaves every User/SuperStockist/
    // Distributor/Shop/Category/CompanySettings row untouched; only
    // catalog + transactional data (products, invoices, payments, stock,
    // product ledger, returns, custom pricing) is cleared.
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reset-transactional-data")
    public ResponseEntity<ApiResponse<String>> resetTransactionalData() {
        return ResponseEntity.ok(dataResetService.resetCatalogAndTransactionalData());
    }
}
