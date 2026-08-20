package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.StockTransferResponse;
import com.spartan.dms.service.StockTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stock-transfers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StockTransferController {

    private final StockTransferService stockTransferService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/company-to-super-stockist")
    public ResponseEntity<ApiResponse<List<StockTransferResponse>>> getCompanyToSuperStockistTransfers() {
        return ResponseEntity.ok(stockTransferService.getCompanyToSuperStockistTransfers());
    }

    // Resolves to the logged-in Super Stockist/Distributor's own incoming transfers.
    @PreAuthorize("hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping("/me/incoming")
    public ResponseEntity<ApiResponse<List<StockTransferResponse>>> getMyIncoming() {
        return ResponseEntity.ok(stockTransferService.getMyIncoming());
    }

    // The logged-in Super Stockist's dispatch history to their own distributors.
    @PreAuthorize("hasRole('SUPER_STOCKIST')")
    @GetMapping("/me/dispatch-history")
    public ResponseEntity<ApiResponse<List<StockTransferResponse>>> getMyDispatchHistory() {
        return ResponseEntity.ok(stockTransferService.getMyDispatchHistory());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @GetMapping("/super-stockist/{id}/incoming")
    public ResponseEntity<ApiResponse<List<StockTransferResponse>>> getIncomingForSuperStockist(
            @PathVariable Long id) {
        return ResponseEntity.ok(stockTransferService.getIncomingForSuperStockist(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @GetMapping("/super-stockist/{id}/dispatch-history")
    public ResponseEntity<ApiResponse<List<StockTransferResponse>>> getDispatchHistoryForSuperStockist(
            @PathVariable Long id) {
        return ResponseEntity.ok(stockTransferService.getDispatchHistoryForSuperStockist(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping("/distributor/{id}/incoming")
    public ResponseEntity<ApiResponse<List<StockTransferResponse>>> getIncomingForDistributor(
            @PathVariable Long id) {
        return ResponseEntity.ok(stockTransferService.getIncomingForDistributor(id));
    }
}
