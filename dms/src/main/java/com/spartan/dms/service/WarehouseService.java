package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.WarehouseResponse;
import com.spartan.dms.entity.Warehouse;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.WarehouseRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Scoped stock viewing across all 3 tiers. Company-level stock lives on
 * Product.stockQuantity directly (see Warehouse entity javadoc); Super
 * Stockist and Distributor stock are Warehouse rows.
 */
@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final com.spartan.dms.repository.ProductDistributorPriceRepository productDistributorPriceRepository;
    private final com.spartan.dms.repository.ProductSuperStockistPriceRepository productSuperStockistPriceRepository;
    private final SecurityUtils securityUtils;

    public ApiResponse<List<WarehouseResponse>> getMyWarehouse() {

        List<Warehouse> rows;
        if (securityUtils.isSuperStockist()) {
            rows = warehouseRepository.findBySuperStockistId(securityUtils.getScopedSuperStockistId());
        } else if (securityUtils.isAdmin()) {
            rows = warehouseRepository.findAll();
        } else {
            rows = warehouseRepository.findByDistributorId(securityUtils.getScopedDistributorId());
        }

        return ApiResponse.<List<WarehouseResponse>>builder()
                .success(true)
                .message("Warehouse stock")
                .data(rows.stream().map(this::toResponse).collect(Collectors.toList()))
                .build();
    }

    public ApiResponse<List<WarehouseResponse>> getSuperStockistWarehouse(Long superStockistId) {

        securityUtils.assertSuperStockistAccess(superStockistId);

        List<Warehouse> rows = warehouseRepository.findBySuperStockistId(superStockistId);

        return ApiResponse.<List<WarehouseResponse>>builder()
                .success(true)
                .message("Super Stockist warehouse stock")
                .data(rows.stream().map(this::toResponse).collect(Collectors.toList()))
                .build();
    }

    public ApiResponse<List<WarehouseResponse>> getDistributorWarehouse(Long distributorId) {

        securityUtils.assertDistributorAccess(distributorId);

        List<Warehouse> rows = warehouseRepository.findByDistributorId(distributorId);

        return ApiResponse.<List<WarehouseResponse>>builder()
                .success(true)
                .message("Distributor warehouse stock")
                .data(rows.stream().map(this::toResponse).collect(Collectors.toList()))
                .build();
    }

    // Admin-only: the Company root stock, straight off Product.stockQuantity.
    public ApiResponse<List<WarehouseResponse>> getCompanyStock() {

        if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Only an admin can view Company stock");
        }

        List<WarehouseResponse> responses = productRepository.findAll().stream()
                .map(p -> WarehouseResponse.builder()
                        .ownerType("COMPANY")
                        .productId(p.getId())
                        .productName(p.getProductName())
                        .productCode(p.getProductCode())
                        .quantity(p.getStockQuantity())
                        .mrp(p.getMrp())
                        .unitPrice(p.getSsPrice())
                        .build())
                .collect(Collectors.toList());

        return ApiResponse.<List<WarehouseResponse>>builder()
                .success(true)
                .message("Company stock")
                .data(responses)
                .build();
    }

    private WarehouseResponse toResponse(Warehouse w) {
        return WarehouseResponse.builder()
                .id(w.getId())
                .ownerType(w.getOwnerType() != null ? w.getOwnerType().name() : null)
                .superStockistId(w.getSuperStockist() != null ? w.getSuperStockist().getId() : null)
                .superStockistName(w.getSuperStockist() != null ? w.getSuperStockist().getSuperStockistName() : null)
                .distributorId(w.getDistributor() != null ? w.getDistributor().getId() : null)
                .distributorName(w.getDistributor() != null ? w.getDistributor().getDistributorName() : null)
                .productId(w.getProduct() != null ? w.getProduct().getId() : null)
                .productName(w.getProduct() != null ? w.getProduct().getProductName() : null)
                .productCode(w.getProduct() != null ? w.getProduct().getProductCode() : null)
                .quantity(w.getQuantity())
                .mrp(w.getProduct() != null ? w.getProduct().getMrp() : null)
                .unitPrice(tierPrice(w))
                .build();
    }

    // The rate THIS warehouse's owner actually bought at. Mirrors
    // PurchaseReturnService.resolveRolePrice() exactly -- a per-party
    // negotiated rate wins over the product's default tier rate -- so the
    // price shown on the Purchase Return screen is the same number the
    // backend will credit. Deliberately not MRP: returns credit what the
    // owner paid, not the retail price.
    private java.math.BigDecimal tierPrice(Warehouse w) {
        if (w.getProduct() == null) {
            return null;
        }
        Long productId = w.getProduct().getId();
        if (w.getOwnerType() == com.spartan.dms.enums.OwnerType.SUPER_STOCKIST && w.getSuperStockist() != null) {
            return productSuperStockistPriceRepository
                    .findByProductIdAndSuperStockistId(productId, w.getSuperStockist().getId())
                    .map(com.spartan.dms.entity.ProductSuperStockistPrice::getPrice)
                    .orElse(w.getProduct().getSsPrice());
        }
        if (w.getDistributor() != null) {
            return productDistributorPriceRepository
                    .findByProductIdAndDistributorId(productId, w.getDistributor().getId())
                    .map(com.spartan.dms.entity.ProductDistributorPrice::getPrice)
                    .orElse(w.getProduct().getDistributorPrice());
        }
        return w.getProduct().getDistributorPrice();
    }
}
