package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.CategorySalesResponse;
import com.spartan.dms.dto.PartySalesRaw;
import com.spartan.dms.dto.PartySalesResponse;
import com.spartan.dms.dto.TopProductResponse;
import com.spartan.dms.enums.InvoiceLevel;
import com.spartan.dms.repository.SalesAnalysisRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Party-wise / Product-wise / Category-wise sales analysis, with the same
 * role-based scope for all three report types so a user always sees the
 * same "slice" of the business no matter which tab they're on:
 *
 *   Admin, no drill-down id   -> Company's own sales, grouped by Super Stockist
 *   Admin/SS + superStockistId -> that Super Stockist's sales, grouped by Distributor
 *   Admin/SS/Distributor + distributorId -> that Distributor's sales, grouped by Shop
 *
 * A Super Stockist login with no drill-down id automatically sees their
 * own network (as if they'd passed their own superStockistId); a
 * Distributor login with no drill-down id automatically sees their own
 * shops (as if they'd passed their own distributorId). This is the exact
 * same auto-scoping idea ProductLedgerService already uses.
 *
 * Passing an explicit id is how the frontend implements "click a Super
 * Stockist's name to drill into their Distributors" — SecurityUtils
 * still enforces that the id belongs to the caller, so a Distributor
 * can never drill into another distributor's data by guessing an id.
 */
@Service
@RequiredArgsConstructor
public class SalesAnalysisService {

    private final SalesAnalysisRepository salesAnalysisRepository;
    private final SecurityUtils securityUtils;

    /** Which slice of the business the current request resolves to. */
    private record Scope(InvoiceLevel level, Long superStockistId, Long distributorId) {}

    private Scope resolveScope(Long superStockistId, Long distributorId) {

        if (distributorId != null) {
            securityUtils.assertDistributorAccess(distributorId);
            return new Scope(InvoiceLevel.DISTRIBUTOR_TO_SHOP, null, distributorId);
        }

        if (superStockistId != null) {
            securityUtils.assertSuperStockistAccess(superStockistId);
            return new Scope(InvoiceLevel.SUPER_STOCKIST_TO_DISTRIBUTOR, superStockistId, null);
        }

        if (securityUtils.isDistributor()) {
            return new Scope(InvoiceLevel.DISTRIBUTOR_TO_SHOP, null, securityUtils.getScopedDistributorId());
        }

        if (securityUtils.isSuperStockist()) {
            return new Scope(InvoiceLevel.SUPER_STOCKIST_TO_DISTRIBUTOR, securityUtils.getScopedSuperStockistId(), null);
        }

        securityUtils.assertAdmin();
        return new Scope(InvoiceLevel.COMPANY_TO_SUPER_STOCKIST, null, null);
    }

    public ApiResponse<List<PartySalesResponse>> getPartyWiseSales(Long superStockistId, Long distributorId) {

        Scope scope = resolveScope(superStockistId, distributorId);
        List<PartySalesRaw> raw;
        String partyType;

        switch (scope.level()) {
            case COMPANY_TO_SUPER_STOCKIST -> {
                raw = salesAnalysisRepository.partySalesBySuperStockist();
                partyType = "SUPER_STOCKIST";
            }
            case SUPER_STOCKIST_TO_DISTRIBUTOR -> {
                raw = salesAnalysisRepository.partySalesByDistributorForSuperStockist(scope.superStockistId());
                partyType = "DISTRIBUTOR";
            }
            default -> {
                raw = salesAnalysisRepository.partySalesByShopForDistributor(scope.distributorId());
                partyType = "SHOP";
            }
        }

        List<PartySalesResponse> data = raw.stream()
                .map(r -> PartySalesResponse.builder()
                        .partyId(r.getPartyId())
                        .partyName(r.getPartyName())
                        .partyType(partyType)
                        .invoiceCount(r.getInvoiceCount())
                        .totalSales(r.getTotalSales())
                        .build())
                .toList();

        return ApiResponse.<List<PartySalesResponse>>builder()
                .success(true)
                .message("Party-wise Sales Analysis")
                .data(data)
                .build();
    }

    public ApiResponse<List<TopProductResponse>> getProductWiseSales(Long superStockistId, Long distributorId) {

        Scope scope = resolveScope(superStockistId, distributorId);

        List<TopProductResponse> data = switch (scope.level()) {
            case COMPANY_TO_SUPER_STOCKIST -> salesAnalysisRepository.productSalesAtCompanyLevel();
            case SUPER_STOCKIST_TO_DISTRIBUTOR -> salesAnalysisRepository.productSalesForSuperStockist(scope.superStockistId());
            default -> salesAnalysisRepository.productSalesForDistributor(scope.distributorId());
        };

        return ApiResponse.<List<TopProductResponse>>builder()
                .success(true)
                .message("Product-wise Sales Analysis")
                .data(data)
                .build();
    }

    public ApiResponse<List<CategorySalesResponse>> getCategoryWiseSales(Long superStockistId, Long distributorId) {

        Scope scope = resolveScope(superStockistId, distributorId);

        List<CategorySalesResponse> data = switch (scope.level()) {
            case COMPANY_TO_SUPER_STOCKIST -> salesAnalysisRepository.categorySalesAtCompanyLevel();
            case SUPER_STOCKIST_TO_DISTRIBUTOR -> salesAnalysisRepository.categorySalesForSuperStockist(scope.superStockistId());
            default -> salesAnalysisRepository.categorySalesForDistributor(scope.distributorId());
        };

        return ApiResponse.<List<CategorySalesResponse>>builder()
                .success(true)
                .message("Category-wise Sales Analysis")
                .data(data)
                .build();
    }
}
