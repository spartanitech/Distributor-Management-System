package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.StockTransferResponse;
import com.spartan.dms.entity.StockTransfer;
import com.spartan.dms.repository.StockTransferRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only views over the StockTransfer audit trail. Stock actually moves
 * only through ProductRequestService.fulfill() (Distributor/Super Stockist
 * request -> Admin/Super Stockist approve+fulfill) — that is the single
 * place Warehouse rows and Product.stockQuantity are debited/credited, and
 * it already writes the StockTransfer row this service reads back. There
 * is intentionally no direct "create transfer" endpoint here: adding one
 * would be a second, competing way to move stock outside the request/
 * approval workflow.
 */
@Service
@RequiredArgsConstructor
public class StockTransferService {

    private final StockTransferRepository stockTransferRepository;
    private final SecurityUtils securityUtils;

    // Admin: every Company -> Super Stockist transfer (the top of the chain).
    public ApiResponse<List<StockTransferResponse>> getCompanyToSuperStockistTransfers() {
        if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Only an admin can view this");
        }
        List<StockTransfer> rows = stockTransferRepository.findByFromSuperStockistIsNullOrderByTransferDateDesc();
        return wrap(rows, "Company -> Super Stockist transfers");
    }

    // Admin or that Super Stockist: what they received from Admin.
    public ApiResponse<List<StockTransferResponse>> getIncomingForSuperStockist(Long superStockistId) {
        securityUtils.assertSuperStockistAccess(superStockistId);
        List<StockTransfer> rows = stockTransferRepository.findByToSuperStockistIdOrderByTransferDateDesc(superStockistId);
        return wrap(rows, "Incoming transfers");
    }

    // Admin or that Super Stockist: everything they've dispatched to their
    // own distributors — the "Dispatch History" sidebar page.
    public ApiResponse<List<StockTransferResponse>> getDispatchHistoryForSuperStockist(Long superStockistId) {
        securityUtils.assertSuperStockistAccess(superStockistId);
        List<StockTransfer> rows = stockTransferRepository.findByFromSuperStockistIdOrderByTransferDateDesc(superStockistId);
        return wrap(rows, "Dispatch history");
    }

    // Admin, the owning Super Stockist, or that Distributor: what a
    // distributor has received.
    public ApiResponse<List<StockTransferResponse>> getIncomingForDistributor(Long distributorId) {
        securityUtils.assertDistributorAccess(distributorId);
        List<StockTransfer> rows = stockTransferRepository.findByToDistributorIdOrderByTransferDateDesc(distributorId);
        return wrap(rows, "Incoming transfers");
    }

    // Convenience: resolves "me" for whichever role is calling.
    public ApiResponse<List<StockTransferResponse>> getMyIncoming() {
        if (securityUtils.isSuperStockist()) {
            return getIncomingForSuperStockist(securityUtils.getScopedSuperStockistId());
        }
        return getIncomingForDistributor(securityUtils.getScopedDistributorId());
    }

    public ApiResponse<List<StockTransferResponse>> getMyDispatchHistory() {
        return getDispatchHistoryForSuperStockist(securityUtils.getScopedSuperStockistId());
    }

    private ApiResponse<List<StockTransferResponse>> wrap(List<StockTransfer> rows, String message) {
        List<StockTransferResponse> responses = rows.stream().map(this::toResponse).collect(Collectors.toList());
        return ApiResponse.<List<StockTransferResponse>>builder()
                .success(true)
                .message(message)
                .data(responses)
                .build();
    }

    private StockTransferResponse toResponse(StockTransfer t) {
        return StockTransferResponse.builder()
                .id(t.getId())
                .fromType(t.getFromType() != null ? t.getFromType().name() : null)
                .fromSuperStockistId(t.getFromSuperStockist() != null ? t.getFromSuperStockist().getId() : null)
                .fromSuperStockistName(t.getFromSuperStockist() != null ? t.getFromSuperStockist().getSuperStockistName() : null)
                .toType(t.getToType() != null ? t.getToType().name() : null)
                .toSuperStockistId(t.getToSuperStockist() != null ? t.getToSuperStockist().getId() : null)
                .toSuperStockistName(t.getToSuperStockist() != null ? t.getToSuperStockist().getSuperStockistName() : null)
                .toDistributorId(t.getToDistributor() != null ? t.getToDistributor().getId() : null)
                .toDistributorName(t.getToDistributor() != null ? t.getToDistributor().getDistributorName() : null)
                .productId(t.getProduct() != null ? t.getProduct().getId() : null)
                .productName(t.getProduct() != null ? t.getProduct().getProductName() : null)
                .productCode(t.getProduct() != null ? t.getProduct().getProductCode() : null)
                .quantity(t.getQuantity())
                .transferDate(t.getTransferDate())
                .transferredBy(t.getTransferredBy())
                .status(t.getStatus() != null ? t.getStatus().name() : null)
                .remarks(t.getRemarks())
                .build();
    }
}
