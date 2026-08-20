package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PagedResponse;
import com.spartan.dms.dto.ProductLedgerResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Product;
import com.spartan.dms.entity.ProductLedger;
import com.spartan.dms.entity.Shop;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.enums.LedgerTransactionType;
import com.spartan.dms.enums.OwnerType;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.repository.ProductLedgerRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Single write path for the Product Ledger, and the only place that reads
 * it back out for the UI.
 *
 * WRITE SIDE — record(...): called only from other services (InvoiceService,
 * SalesReturnService, ProductService, ProductRequestService) at the exact
 * moment they already mutate Product.stockQuantity or a Warehouse row, in
 * the same @Transactional unit of work as that mutation. There is no
 * controller endpoint that lets a user create, edit or delete a ledger
 * row directly — that is what makes the ledger read-only from the
 * outside. Every entry is derived: the running balance is computed here
 * from the previous entry at the same (product, location), never trusted
 * from the caller, so it can't drift from reality even if a caller
 * passes a wrong number by mistake.
 *
 * READ SIDE — getProductLedger / getLocationLedger: plain paginated
 * lookups, access-scoped the same way every other report in this
 * codebase is (Admin sees everything, a Super Stockist/Distributor/Shop
 * login only ever sees their own location's rows).
 */
@Service
@RequiredArgsConstructor
public class ProductLedgerService {

    private final ProductLedgerRepository productLedgerRepository;
    private final SecurityUtils securityUtils;
    private final com.spartan.dms.repository.ShopRepository shopRepository;

    // ---------------------------------------------------------------
    // WRITE SIDE (internal — called by other services only)
    // ---------------------------------------------------------------

    /**
     * Appends one Product Ledger row. Exactly one of {inQuantity, outQuantity}
     * should be non-zero (the other pass BigDecimal.ZERO) — the running
     * balance is opening-balance-at-this-location + in - out.
     */
    @Transactional
    public ProductLedger record(LedgerBuilder b) {

        BigDecimal in = b.inQuantity != null ? b.inQuantity : BigDecimal.ZERO;
        BigDecimal out = b.outQuantity != null ? b.outQuantity : BigDecimal.ZERO;
        BigDecimal cost = b.unitCost != null ? b.unitCost : BigDecimal.ZERO;

        BigDecimal previousBalance = previousBalance(b.product.getId(), b.ownerType,
                b.superStockist, b.distributor, b.shop);
        BigDecimal newBalance = previousBalance.add(in).subtract(out);

        BigDecimal stockValue = in.subtract(out).abs().multiply(cost).setScale(2, RoundingMode.HALF_UP);

        ProductLedger entry = ProductLedger.builder()
                .transactionDateTime(b.transactionDateTime != null ? b.transactionDateTime : LocalDateTime.now())
                .voucherNo(b.voucherNo)
                .transactionType(b.transactionType)
                .product(b.product)
                .batchNo(b.batchNo)
                .ownerType(b.ownerType)
                .superStockist(b.superStockist)
                .distributor(b.distributor)
                .shop(b.shop)
                .inQuantity(in)
                .outQuantity(out)
                .balanceQuantity(newBalance)
                .unitCost(cost)
                .stockValue(stockValue)
                .performedBy(b.performedBy)
                .remarks(b.remarks)
                .build();

        return productLedgerRepository.save(entry);
    }

    private BigDecimal previousBalance(Long productId, OwnerType ownerType,
                                        SuperStockist superStockist, Distributor distributor, Shop shop) {

        Pageable one = PageRequest.of(0, 1);
        List<ProductLedger> latest = switch (ownerType) {
            case COMPANY -> productLedgerRepository.findLatestForCompany(productId, one);
            case SUPER_STOCKIST -> productLedgerRepository.findLatestForSuperStockist(
                    productId, superStockist.getId(), one);
            case DISTRIBUTOR -> productLedgerRepository.findLatestForDistributor(
                    productId, distributor.getId(), one);
        };
        return latest.isEmpty() ? BigDecimal.ZERO : latest.get(0).getBalanceQuantity();
    }

    /** Plain constructor-arg carrier so callers don't deal with a 15-arg method. */
    public static class LedgerBuilder {
        public LocalDateTime transactionDateTime;
        public String voucherNo;
        public LedgerTransactionType transactionType;
        public Product product;
        public String batchNo;
        public OwnerType ownerType;
        public SuperStockist superStockist;
        public Distributor distributor;
        public Shop shop;
        public BigDecimal inQuantity;
        public BigDecimal outQuantity;
        public BigDecimal unitCost;
        public String performedBy;
        public String remarks;
    }

    // ---------------------------------------------------------------
    // READ SIDE
    // ---------------------------------------------------------------

    public ApiResponse<PagedResponse<ProductLedgerResponse>> getProductLedger(
            Long productId, Long superStockistId, Long distributorId, Long shopId, int page, int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionDateTime", "id"));
        org.springframework.data.domain.Page<ProductLedger> result;

        if (superStockistId != null) {
            securityUtils.assertSuperStockistAccess(superStockistId);
            result = productLedgerRepository.findByProductIdAndOwnerTypeAndSuperStockistIdOrderByTransactionDateTimeDescIdDesc(
                    productId, OwnerType.SUPER_STOCKIST, superStockistId, pageable);
        } else if (distributorId != null) {
            securityUtils.assertDistributorAccess(distributorId);
            result = productLedgerRepository.findByProductIdAndOwnerTypeAndDistributorIdOrderByTransactionDateTimeDescIdDesc(
                    productId, OwnerType.DISTRIBUTOR, distributorId, pageable);
        } else if (shopId != null) {
            // A shop has no login of its own, so its ledger belongs to the
            // distributor who owns it -- resolve that owner and run the
            // SAME check the distributorId branch does. Without this, any
            // authenticated user could pass an arbitrary shopId and read
            // another distributor's shop's purchase history (products,
            // quantities and rates), which is exactly the data the
            // distributorId branch above is careful to protect.
            com.spartan.dms.entity.Shop shop = shopRepository.findById(shopId)
                    .orElseThrow(() -> new com.spartan.dms.exception.ResourceNotFoundException("Shop not found"));
            securityUtils.assertDistributorAccess(
                    shop.getDistributor() != null ? shop.getDistributor().getId() : null);
            result = productLedgerRepository.findByProductIdAndShopIdOrderByTransactionDateTimeDescIdDesc(
                    productId, shopId, pageable);
        } else {
            // No location filter = the full cross-hierarchy history for
            // this product — Admin only, since it spans every party's data.
            securityUtils.assertAdmin();
            result = productLedgerRepository.findByProductIdOrderByTransactionDateTimeDescIdDesc(productId, pageable);
        }

        return ApiResponse.<PagedResponse<ProductLedgerResponse>>builder()
                .success(true)
                .message("Product Ledger")
                .data(toPagedResponse(result))
                .build();
    }

    /** "My warehouse's ledger" — every product, scoped to the caller's own location. */
    public ApiResponse<PagedResponse<ProductLedgerResponse>> getMyLocationLedger(int page, int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionDateTime", "id"));
        org.springframework.data.domain.Page<ProductLedger> result;

        if (securityUtils.isSuperStockist()) {
            result = productLedgerRepository.findByOwnerTypeAndSuperStockistIdOrderByTransactionDateTimeDescIdDesc(
                    OwnerType.SUPER_STOCKIST, securityUtils.getScopedSuperStockistId(), pageable);
        } else if (securityUtils.isDistributor()) {
            result = productLedgerRepository.findByOwnerTypeAndDistributorIdOrderByTransactionDateTimeDescIdDesc(
                    OwnerType.DISTRIBUTOR, securityUtils.getScopedDistributorId(), pageable);
        } else {
            throw new ForbiddenException("Admin logins should use getProductLedger with an explicit location filter");
        }

        return ApiResponse.<PagedResponse<ProductLedgerResponse>>builder()
                .success(true)
                .message("Product Ledger")
                .data(toPagedResponse(result))
                .build();
    }

    private PagedResponse<ProductLedgerResponse> toPagedResponse(org.springframework.data.domain.Page<ProductLedger> result) {
        List<ProductLedgerResponse> content = result.getContent().stream().map(this::toResponse).toList();
        return PagedResponse.<ProductLedgerResponse>builder()
                .content(content)
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    private ProductLedgerResponse toResponse(ProductLedger e) {
        Long locationId = null;
        String locationName = null;
        if (e.getOwnerType() == OwnerType.SUPER_STOCKIST && e.getSuperStockist() != null) {
            locationId = e.getSuperStockist().getId();
            locationName = e.getSuperStockist().getSuperStockistName();
        } else if (e.getOwnerType() == OwnerType.DISTRIBUTOR && e.getDistributor() != null) {
            locationId = e.getDistributor().getId();
            locationName = e.getDistributor().getDistributorName();
        } else if (e.getShop() != null) {
            locationId = e.getShop().getId();
            locationName = e.getShop().getShopName();
        } else if (e.getOwnerType() == OwnerType.COMPANY) {
            locationName = "Company";
        }

        return ProductLedgerResponse.builder()
                .id(e.getId())
                .transactionDateTime(e.getTransactionDateTime())
                .voucherNo(e.getVoucherNo())
                .transactionType(e.getTransactionType() != null ? e.getTransactionType().name() : null)
                .productId(e.getProduct() != null ? e.getProduct().getId() : null)
                .productName(e.getProduct() != null ? e.getProduct().getProductName() : null)
                .productCode(e.getProduct() != null ? e.getProduct().getProductCode() : null)
                .batchNo(e.getBatchNo())
                .ownerType(e.getOwnerType() != null ? e.getOwnerType().name() : null)
                .locationId(locationId)
                .locationName(locationName)
                .inQuantity(e.getInQuantity())
                .outQuantity(e.getOutQuantity())
                .balanceQuantity(e.getBalanceQuantity())
                .unitCost(e.getUnitCost())
                .stockValue(e.getStockValue())
                .performedBy(e.getPerformedBy())
                .remarks(e.getRemarks())
                .build();
    }
}
