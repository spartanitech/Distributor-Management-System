package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.ShopRequest;
import com.spartan.dms.dto.ShopResponse;
import com.spartan.dms.service.ShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shops")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ShopController {

    private final ShopService shopService;

    // CREATE: id is intentionally absent from ShopRequest, so there is no way
    // for the client to influence which row gets written to. Every POST here
    // results in a brand-new row (see ShopService.createShop / ShopMapper.toEntity).
    // Ownership check (own distributor only) now lives in ShopService via
    // securityUtils.assertDistributorAccess.
    @PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR')")
    @PostMapping
    public ResponseEntity<ApiResponse<ShopResponse>> createShop(
            @Valid @RequestBody ShopRequest request) {

        return ResponseEntity.ok(shopService.createShop(request));
    }

    // UPDATE: the id comes ONLY from the path variable, never from the request
    // body - ShopRequest has no id field for a client to send in the first place.
    // Ownership check (own shop only) now lives in ShopService.
    @PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ShopResponse>> updateShop(
            @PathVariable Long id,
            @Valid @RequestBody ShopRequest request) {

        return ResponseEntity.ok(shopService.updateShop(id, request));
    }

    // Deleting shops stays admin-only per spec ("Cannot delete shops owned by others").
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteShop(
            @PathVariable Long id) {

        return ResponseEntity.ok(shopService.deleteShop(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ShopResponse>> getShopById(
            @PathVariable Long id) {

        return ResponseEntity.ok(shopService.getShopById(id));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ShopResponse>>> getAllShops() {

        return ResponseEntity.ok(shopService.getAllShops());
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ShopResponse>>> searchShop(
            @RequestParam String keyword) {

        return ResponseEntity.ok(shopService.searchShop(keyword));
    }

    @GetMapping("/distributor/{distributorId}")
    public ResponseEntity<ApiResponse<List<ShopResponse>>> getShopsByDistributor(
            @PathVariable Long distributorId) {

        return ResponseEntity.ok(shopService.getShopsByDistributor(distributorId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updateShopStatus(
            @PathVariable Long id,
            @RequestParam Boolean status) {

        return ResponseEntity.ok(shopService.updateShopStatus(id, status));
    }
}