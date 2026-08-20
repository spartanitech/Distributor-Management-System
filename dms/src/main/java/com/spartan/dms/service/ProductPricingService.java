package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PartnerPriceRequest;
import com.spartan.dms.dto.PartnerPriceResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Product;
import com.spartan.dms.entity.ProductDistributorPrice;
import com.spartan.dms.entity.ProductSuperStockistPrice;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.ProductDistributorPriceRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.ProductSuperStockistPriceRepository;
import com.spartan.dms.repository.SuperStockistRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lets Admin give each Super Stockist / Distributor their own negotiated
 * rate on a product, instead of one fixed price applying to everyone at
 * that tier (e.g. SS-A pays ₹50 for Product X, SS-B pays ₹55). Falls back
 * to Product.ssPrice / Product.distributorPrice when no override exists.
 * InvoiceService.resolveTierPrice() calls the two lookup methods here
 * (not the repositories directly) so pricing logic stays in one place.
 */
@Service
@RequiredArgsConstructor
public class ProductPricingService {

    private final ProductRepository productRepository;
    private final SuperStockistRepository superStockistRepository;
    private final DistributorRepository distributorRepository;
    private final ProductSuperStockistPriceRepository ssPriceRepository;
    private final ProductDistributorPriceRepository distributorPriceRepository;
    private final SecurityUtils securityUtils;

    // ---------- resolution (used by InvoiceService) ----------

    /** Effective SS price for this product+superStockist: override if set, else Product.ssPrice (may be null). */
    public BigDecimal resolveSsPrice(Long productId, Long superStockistId) {
        return ssPriceRepository.findByProductIdAndSuperStockistId(productId, superStockistId)
                .map(ProductSuperStockistPrice::getPrice)
                .orElseGet(() -> productRepository.findById(productId).map(Product::getSsPrice).orElse(null));
    }

    /** Effective DP for this product+distributor: override if set, else Product.distributorPrice (may be null). */
    public BigDecimal resolveDistributorPrice(Long productId, Long distributorId) {
        return distributorPriceRepository.findByProductIdAndDistributorId(productId, distributorId)
                .map(ProductDistributorPrice::getPrice)
                .orElseGet(() -> productRepository.findById(productId).map(Product::getDistributorPrice).orElse(null));
    }

    // ---------- admin management ----------

    @Transactional
    public ApiResponse<String> setSsPrice(Long productId, Long superStockistId, PartnerPriceRequest dto) {
        securityUtils.assertAdmin();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        SuperStockist ss = superStockistRepository.findById(superStockistId)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        ProductSuperStockistPrice row = ssPriceRepository.findByProductIdAndSuperStockistId(productId, superStockistId)
                .orElseGet(() -> ProductSuperStockistPrice.builder().product(product).superStockist(ss).build());
        row.setPrice(dto.getPrice());
        ssPriceRepository.save(row);

        return ApiResponse.<String>builder().success(true)
                .message("SS price for " + ss.getSuperStockistName() + " on " + product.getProductName() + " set to " + dto.getPrice())
                .build();
    }

    @Transactional
    public ApiResponse<String> removeSsPriceOverride(Long productId, Long superStockistId) {
        securityUtils.assertAdmin();
        ssPriceRepository.deleteByProductIdAndSuperStockistId(productId, superStockistId);
        return ApiResponse.<String>builder().success(true)
                .message("Override removed — this Super Stockist now falls back to the product's default SS price")
                .build();
    }

    public ApiResponse<List<PartnerPriceResponse>> listSsPrices(Long productId) {
        securityUtils.assertAdmin();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // 2 queries total (all Super Stockists + all this product's overrides
        // in one shot), not 1+N — the old version called
        // findByProductIdAndSuperStockistId() inside the .map() loop, firing
        // one extra SQL query per Super Stockist in the system.
        List<ProductSuperStockistPrice> overrides = ssPriceRepository.findByProductId(productId);
        java.util.Map<Long, ProductSuperStockistPrice> overrideBySsId = overrides.stream()
                .collect(Collectors.toMap(o -> o.getSuperStockist().getId(), o -> o));

        List<PartnerPriceResponse> result = superStockistRepository.findAll().stream()
                .map(ss -> {
                    ProductSuperStockistPrice override = overrideBySsId.get(ss.getId());
                    BigDecimal price = override != null ? override.getPrice() : product.getSsPrice();
                    return PartnerPriceResponse.builder()
                            .productId(productId).productName(product.getProductName())
                            .partnerId(ss.getId()).partnerName(ss.getSuperStockistName())
                            .price(price).overridden(override != null)
                            .build();
                }).collect(Collectors.toList());

        return ApiResponse.<List<PartnerPriceResponse>>builder().success(true).data(result).build();
    }

    @Transactional
    public ApiResponse<String> setDistributorPrice(Long productId, Long distributorId, PartnerPriceRequest dto) {
        securityUtils.assertAdmin();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        Distributor distributor = distributorRepository.findById(distributorId)
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        ProductDistributorPrice row = distributorPriceRepository.findByProductIdAndDistributorId(productId, distributorId)
                .orElseGet(() -> ProductDistributorPrice.builder().product(product).distributor(distributor).build());
        row.setPrice(dto.getPrice());
        distributorPriceRepository.save(row);

        return ApiResponse.<String>builder().success(true)
                .message("Distributor price (DP) for " + distributor.getDistributorName() + " on " + product.getProductName() + " set to " + dto.getPrice())
                .build();
    }

    @Transactional
    public ApiResponse<String> removeDistributorPriceOverride(Long productId, Long distributorId) {
        securityUtils.assertAdmin();
        distributorPriceRepository.deleteByProductIdAndDistributorId(productId, distributorId);
        return ApiResponse.<String>builder().success(true)
                .message("Override removed — this Distributor now falls back to the product's default DP")
                .build();
    }

    public ApiResponse<List<PartnerPriceResponse>> listDistributorPrices(Long productId) {
        securityUtils.assertAdmin();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        List<ProductDistributorPrice> overrides = distributorPriceRepository.findByProductId(productId);
        java.util.Map<Long, ProductDistributorPrice> overrideByDistId = overrides.stream()
                .collect(Collectors.toMap(o -> o.getDistributor().getId(), o -> o));

        List<PartnerPriceResponse> result = distributorRepository.findAll().stream()
                .map(d -> {
                    ProductDistributorPrice override = overrideByDistId.get(d.getId());
                    BigDecimal price = override != null ? override.getPrice() : product.getDistributorPrice();
                    return PartnerPriceResponse.builder()
                            .productId(productId).productName(product.getProductName())
                            .partnerId(d.getId()).partnerName(d.getDistributorName())
                            .price(price).overridden(override != null)
                            .build();
                }).collect(Collectors.toList());

        return ApiResponse.<List<PartnerPriceResponse>>builder().success(true).data(result).build();
    }

    // ---------- self-service: "what do I pay for each product" ----------

    /** For the logged-in Super Stockist: their effective price on every product. */
    public ApiResponse<List<PartnerPriceResponse>> myPricesAsSuperStockist() {
        Long ssId = securityUtils.getScopedSuperStockistId();
        SuperStockist ss = superStockistRepository.findById(ssId)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        // All of this SS's overrides in one query, keyed by productId —
        // the old version called findByProductIdAndSuperStockistId() TWICE
        // per product (once via resolveSsPrice, once for `overridden`),
        // i.e. 2N extra queries for N products.
        java.util.Map<Long, ProductSuperStockistPrice> overrideByProductId = ssPriceRepository.findBySuperStockistId(ssId).stream()
                .collect(Collectors.toMap(o -> o.getProduct().getId(), o -> o));

        List<PartnerPriceResponse> result = productRepository.findAll().stream()
                .map(p -> {
                    ProductSuperStockistPrice override = overrideByProductId.get(p.getId());
                    BigDecimal price = override != null ? override.getPrice() : p.getSsPrice();
                    return PartnerPriceResponse.builder()
                            .productId(p.getId()).productName(p.getProductName())
                            .partnerId(ssId).partnerName(ss.getSuperStockistName())
                            .price(price).overridden(override != null)
                            .build();
                }).collect(Collectors.toList());
        return ApiResponse.<List<PartnerPriceResponse>>builder().success(true).data(result).build();
    }

    /** For the logged-in Distributor: their effective DP on every product. */
    public ApiResponse<List<PartnerPriceResponse>> myPricesAsDistributor() {
        Long distId = securityUtils.getScopedDistributorId();
        Distributor distributor = distributorRepository.findById(distId)
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        java.util.Map<Long, ProductDistributorPrice> overrideByProductId = distributorPriceRepository.findByDistributorId(distId).stream()
                .collect(Collectors.toMap(o -> o.getProduct().getId(), o -> o));

        List<PartnerPriceResponse> result = productRepository.findAll().stream()
                .map(p -> {
                    ProductDistributorPrice override = overrideByProductId.get(p.getId());
                    BigDecimal price = override != null ? override.getPrice() : p.getDistributorPrice();
                    return PartnerPriceResponse.builder()
                            .productId(p.getId()).productName(p.getProductName())
                            .partnerId(distId).partnerName(distributor.getDistributorName())
                            .price(price).overridden(override != null)
                            .build();
                }).collect(Collectors.toList());
        return ApiResponse.<List<PartnerPriceResponse>>builder().success(true).data(result).build();
    }
}
