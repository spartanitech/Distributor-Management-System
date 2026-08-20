package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.ProductRequestActionDto;
import com.spartan.dms.dto.ProductRequestCreateDto;
import com.spartan.dms.dto.ProductRequestResponse;
import com.spartan.dms.enums.ProductRequestStatus;
import com.spartan.dms.service.ProductRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/product-requests")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductRequestController {

    private final ProductRequestService productRequestService;

    // All 3 roles may create a request; the service attributes it to the
    // caller's own distributor/super-stockist when the caller isn't an admin.
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @PostMapping
    public ResponseEntity<ApiResponse<ProductRequestResponse>> createRequest(
            @RequestBody ProductRequestCreateDto dto) {

        return ResponseEntity.ok(productRequestService.createRequest(dto));
    }

    // Admin sees all (optionally filtered); a Super Stockist sees requests
    // routed to them plus their own requests to Admin (narrow with level=
    // DISTRIBUTOR_TO_SUPER_STOCKIST or SUPER_STOCKIST_TO_COMPANY); a
    // distributor sees only their own — filtering happens server-side.
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductRequestResponse>>> getRequests(
            @RequestParam(required = false) ProductRequestStatus status,
            @RequestParam(required = false) com.spartan.dms.enums.RequestLevel level) {

        return ResponseEntity.ok(productRequestService.getRequests(status, level));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductRequestResponse>> getRequestById(
            @PathVariable Long id) {

        return ResponseEntity.ok(productRequestService.getRequestById(id));
    }

    @PreAuthorize("hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<ProductRequestResponse>> cancelRequest(
            @PathVariable Long id) {

        return ResponseEntity.ok(productRequestService.cancelRequest(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @PutMapping("/{id}/action")
    public ResponseEntity<ApiResponse<ProductRequestResponse>> actionRequest(
            @PathVariable Long id,
            @RequestBody ProductRequestActionDto dto) {

        return ResponseEntity.ok(productRequestService.actionRequest(id, dto));
    }
}
