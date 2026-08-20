package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PurchaseReturnRequest;
import com.spartan.dms.dto.PurchaseReturnResponse;
import com.spartan.dms.service.PurchaseReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Returns travelling UP the chain. Deliberately split into separate,
 * role-specific endpoints rather than one generic "create return" API:
 * each tier can only ever file its own return, and the sending party is
 * resolved server-side from the caller's login, so there is no request
 * field a client could tamper with to file on someone else's behalf.
 *
 * There is NO create endpoint for Admin -- Company is the top of the
 * chain, so there is nothing above it to return to. Admin gets read-only
 * history/detail views instead.
 */
@RestController
@RequestMapping("/api/v1/purchase-returns")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PurchaseReturnController {

    private final PurchaseReturnService purchaseReturnService;

    /* ---------- Distributor ---------- */

    // Distributor -> Super Stockist. Admin is intentionally NOT allowed:
    // the sending distributor comes from the caller's own login, so there
    // is no way to express "on behalf of distributor X" here.
    @PreAuthorize("hasRole('DISTRIBUTOR')")
    @PostMapping("/distributor")
    public ResponseEntity<ApiResponse<PurchaseReturnResponse>> createDistributorPurchaseReturn(
            @Valid @RequestBody PurchaseReturnRequest request) {

        return ResponseEntity.ok(purchaseReturnService.createDistributorPurchaseReturn(request));
    }

    @PreAuthorize("hasRole('DISTRIBUTOR')")
    @GetMapping("/distributor/me")
    public ResponseEntity<ApiResponse<List<PurchaseReturnResponse>>> getMyDistributorPurchaseReturns() {

        return ResponseEntity.ok(purchaseReturnService.getMyDistributorPurchaseReturns());
    }

    /* ---------- Super Stockist ---------- */

    // Super Stockist -> Company.
    @PreAuthorize("hasRole('SUPER_STOCKIST')")
    @PostMapping("/super-stockist")
    public ResponseEntity<ApiResponse<PurchaseReturnResponse>> createSuperStockistPurchaseReturn(
            @Valid @RequestBody PurchaseReturnRequest request) {

        return ResponseEntity.ok(purchaseReturnService.createSuperStockistPurchaseReturn(request));
    }

    @PreAuthorize("hasRole('SUPER_STOCKIST')")
    @GetMapping("/super-stockist/me")
    public ResponseEntity<ApiResponse<List<PurchaseReturnResponse>>> getMySuperStockistPurchaseReturns() {

        return ResponseEntity.ok(purchaseReturnService.getMySuperStockistPurchaseReturns());
    }

    // The SAME rows the Super Stockist's distributors filed as purchase
    // returns, read from the receiving side -- their "Sales Return" page.
    @PreAuthorize("hasRole('SUPER_STOCKIST')")
    @GetMapping("/super-stockist/me/received")
    public ResponseEntity<ApiResponse<List<PurchaseReturnResponse>>> getMySuperStockistSalesReturns() {

        return ResponseEntity.ok(purchaseReturnService.getMySuperStockistSalesReturns());
    }

    /* ---------- Admin (read-only) ---------- */

    // Everything Super Stockists returned up into Company stock.
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/company/received")
    public ResponseEntity<ApiResponse<List<PurchaseReturnResponse>>> getCompanyInboundReturns() {

        return ResponseEntity.ok(purchaseReturnService.getCompanyInboundReturns());
    }

    // Complete cross-hierarchy return history.
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<PurchaseReturnResponse>>> getAllReturns() {

        return ResponseEntity.ok(purchaseReturnService.getAllReturns());
    }

    /* ---------- Approval ---------- */

    // The RECEIVING party approves, since they're accepting goods back
    // into their own stock: a Super Stockist for their distributors'
    // returns, an Admin for a Super Stockist's return into Company stock.
    // Enforced in the service (assertCanDecide) -- this annotation only
    // keeps distributors, who never approve anything, out entirely.
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<PurchaseReturnResponse>> approveReturn(@PathVariable Long id) {

        return ResponseEntity.ok(purchaseReturnService.approveReturn(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<PurchaseReturnResponse>> rejectReturn(
            @PathVariable Long id,
            @RequestBody(required = false) java.util.Map<String, String> body) {

        return ResponseEntity.ok(
                purchaseReturnService.rejectReturn(id, body != null ? body.get("reason") : null));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<PurchaseReturnResponse>>> getPendingForMe() {

        return ResponseEntity.ok(purchaseReturnService.getPendingForMe());
    }

    // Detail view -- access-scoped in the service to the parties involved.
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PurchaseReturnResponse>> getById(@PathVariable Long id) {

        return ResponseEntity.ok(purchaseReturnService.getById(id));
    }

    // Deletes a return and reverses the stock at both ends. Ownership and
    // "has the receiver already moved the stock on?" are both checked in
    // the service. There is intentionally no PUT: see
    // PurchaseReturnService.deletePurchaseReturn() for why a posted return
    // is delete-and-re-enter rather than editable.
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deletePurchaseReturn(@PathVariable Long id) {

        return ResponseEntity.ok(purchaseReturnService.deletePurchaseReturn(id));
    }

    /**
     * Return register PDF. `scope` selects which of the role-specific
     * views to print (distributor | super-stockist | received |
     * company-received | history); each delegates to the matching read
     * method, so a caller can only ever print rows they can already see.
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportReturnsPdf(
            @RequestParam(defaultValue = "history") String scope) {

        byte[] pdf = purchaseReturnService.exportReturnsPdf(scope);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=returns-" + scope + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
