package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.CategorySalesResponse;
import com.spartan.dms.dto.PartySalesResponse;
import com.spartan.dms.dto.TopProductResponse;
import com.spartan.dms.service.SalesAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only. Every endpoint takes the same two optional drill-down params:
 *   - no params            -> caller's own top-level view (Admin: by Super
 *                              Stockist; Super Stockist: by their own
 *                              Distributors; Distributor: by their own Shops)
 *   - ?superStockistId=X   -> that Super Stockist's Distributors
 *   - ?distributorId=X     -> that Distributor's Shops
 * Access to a given id is enforced server-side (SecurityUtils) — a
 * Distributor or Super Stockist login can never pass another party's id
 * and see their data.
 */
@RestController
@RequestMapping("/api/v1/sales-analysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
public class SalesAnalysisController {

    private final SalesAnalysisService salesAnalysisService;

    @GetMapping("/party-wise")
    public ResponseEntity<ApiResponse<List<PartySalesResponse>>> getPartyWiseSales(
            @RequestParam(required = false) Long superStockistId,
            @RequestParam(required = false) Long distributorId) {

        return ResponseEntity.ok(salesAnalysisService.getPartyWiseSales(superStockistId, distributorId));
    }

    @GetMapping("/product-wise")
    public ResponseEntity<ApiResponse<List<TopProductResponse>>> getProductWiseSales(
            @RequestParam(required = false) Long superStockistId,
            @RequestParam(required = false) Long distributorId) {

        return ResponseEntity.ok(salesAnalysisService.getProductWiseSales(superStockistId, distributorId));
    }

    @GetMapping("/category-wise")
    public ResponseEntity<ApiResponse<List<CategorySalesResponse>>> getCategoryWiseSales(
            @RequestParam(required = false) Long superStockistId,
            @RequestParam(required = false) Long distributorId) {

        return ResponseEntity.ok(salesAnalysisService.getCategoryWiseSales(superStockistId, distributorId));
    }
}
