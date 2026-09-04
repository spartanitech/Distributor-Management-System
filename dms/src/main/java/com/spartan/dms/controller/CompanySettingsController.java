package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.CompanySettingsRequest;
import com.spartan.dms.dto.CompanySettingsResponse;
import com.spartan.dms.service.CompanySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-settings")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CompanySettingsController {

    private final CompanySettingsService companySettingsService;

    // Any logged-in role can read this — it's what gets printed as the
    // seller block on Company -> Super Stockist invoices, and the
    // Settings screen shows it read-only to non-admins.
    @GetMapping
    public ResponseEntity<ApiResponse<CompanySettingsResponse>> get() {
        return ResponseEntity.ok(companySettingsService.get());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<ApiResponse<CompanySettingsResponse>> update(
            @RequestBody CompanySettingsRequest request) {
        return ResponseEntity.ok(companySettingsService.update(request));
    }
}
