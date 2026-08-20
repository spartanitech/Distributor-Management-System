package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PartnerPriceRequest;
import com.spartan.dms.dto.PartnerPriceResponse;
import com.spartan.dms.service.ProductPricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/product-pricing")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductPricingController {

    private final ProductPricingService pricingService;

    // ---- Admin: per-Super-Stockist SS-price overrides ----

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/products/{productId}/ss-prices")
    public ApiResponse<List<PartnerPriceResponse>> listSsPrices(@PathVariable Long productId) {
        return pricingService.listSsPrices(productId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/products/{productId}/ss-prices/{superStockistId}")
    public ApiResponse<String> setSsPrice(@PathVariable Long productId, @PathVariable Long superStockistId,
                                           @Valid @RequestBody PartnerPriceRequest request) {
        return pricingService.setSsPrice(productId, superStockistId, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/products/{productId}/ss-prices/{superStockistId}")
    public ApiResponse<String> removeSsPriceOverride(@PathVariable Long productId, @PathVariable Long superStockistId) {
        return pricingService.removeSsPriceOverride(productId, superStockistId);
    }

    // ---- Admin: per-Distributor DP overrides ----

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/products/{productId}/distributor-prices")
    public ApiResponse<List<PartnerPriceResponse>> listDistributorPrices(@PathVariable Long productId) {
        return pricingService.listDistributorPrices(productId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/products/{productId}/distributor-prices/{distributorId}")
    public ApiResponse<String> setDistributorPrice(@PathVariable Long productId, @PathVariable Long distributorId,
                                                     @Valid @RequestBody PartnerPriceRequest request) {
        return pricingService.setDistributorPrice(productId, distributorId, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/products/{productId}/distributor-prices/{distributorId}")
    public ApiResponse<String> removeDistributorPriceOverride(@PathVariable Long productId, @PathVariable Long distributorId) {
        return pricingService.removeDistributorPriceOverride(productId, distributorId);
    }

    // ---- Self-service: "what do I pay for each product" ----

    @PreAuthorize("hasRole('SUPER_STOCKIST')")
    @GetMapping("/me/as-super-stockist")
    public ApiResponse<List<PartnerPriceResponse>> myPricesAsSuperStockist() {
        return pricingService.myPricesAsSuperStockist();
    }

    @PreAuthorize("hasRole('DISTRIBUTOR')")
    @GetMapping("/me/as-distributor")
    public ApiResponse<List<PartnerPriceResponse>> myPricesAsDistributor() {
        return pricingService.myPricesAsDistributor();
    }
}
