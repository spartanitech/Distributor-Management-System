package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PagedResponse;
import com.spartan.dms.dto.ProductLedgerResponse;
import com.spartan.dms.service.ProductLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only. There is no POST/PUT/PATCH/DELETE endpoint anywhere in this
 * controller on purpose — every Product Ledger row is written internally
 * by InvoiceService, SalesReturnService, ProductService and
 * ProductRequestService at the moment the underlying stock actually
 * moves. Users can view the ledger; nothing lets them edit it.
 */
@RestController
@RequestMapping("/api/v1/product-ledger")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
public class ProductLedgerController {

    private final ProductLedgerService productLedgerService;

    /**
     * Full history for one product. Admin can call this with no location
     * filter to see every level at once; pass superStockistId /
     * distributorId to scope to one node in the hierarchy (also usable
     * by a Super Stockist / Distributor login to see their own copy).
     */
    @GetMapping("/product/{productId}")
    public ResponseEntity<ApiResponse<PagedResponse<ProductLedgerResponse>>> getProductLedger(
            @PathVariable Long productId,
            @RequestParam(required = false) Long superStockistId,
            @RequestParam(required = false) Long distributorId,
            @RequestParam(required = false) Long shopId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(productLedgerService.getProductLedger(
                productId, superStockistId, distributorId, shopId, page, size));
    }

    /**
     * Every product, scoped to the caller's own location — the "my stock
     * ledger" view for a Super Stockist / Distributor login.
     */
    @GetMapping("/my-location")
    public ResponseEntity<ApiResponse<PagedResponse<ProductLedgerResponse>>> getMyLocationLedger(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(productLedgerService.getMyLocationLedger(page, size));
    }
}
