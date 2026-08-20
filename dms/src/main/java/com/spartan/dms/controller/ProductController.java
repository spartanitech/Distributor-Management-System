package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.ProductRequest;
import com.spartan.dms.dto.ProductResponse;
import com.spartan.dms.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductController {

    private final ProductService productService;

    /**
     * CREATE. Deliberately takes no {id} path variable and the body DTO
     * (ProductRequest) has no id field — there is no way for a client to
     * supply an id here, which is what keeps this endpoint a pure INSERT.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestBody ProductRequest request) {

        ApiResponse<ProductResponse> response = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * UPDATE. The id always comes from the URL path, never the body — this
     * is what cleanly separates "create" from "update" at the API layer.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {

        return ResponseEntity.ok(productService.updateProduct(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteProduct(
            @PathVariable Long id) {

        return ResponseEntity.ok(productService.deleteProduct(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductById(
            @PathVariable Long id) {

        return ResponseEntity.ok(productService.getProductById(id));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getAllProducts() {

        return ResponseEntity.ok(productService.getAllProducts());
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> searchProduct(
            @RequestParam String keyword) {

        return ResponseEntity.ok(productService.searchProduct(keyword));
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getProductsByCategory(
            @PathVariable Long categoryId) {

        return ResponseEntity.ok(productService.getProductsByCategory(categoryId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updateProductStatus(
            @PathVariable Long id,
            @RequestParam Boolean status) {

        return ResponseEntity.ok(productService.updateProductStatus(id, status));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/stock")
    public ResponseEntity<ApiResponse<String>> updateStock(
            @PathVariable Long id,
            @RequestParam Integer quantity) {

        return ResponseEntity.ok(productService.updateStock(id, quantity));
    }

    /**
     * Additive stock-IN entry (purchase / manual correction) — unlike
     * PATCH .../stock above (which blind-overwrites the count), this ADDS
     * to current stock and logs an INWARD row in stock_movements so the
     * Stock Summary report has real Inward history to show.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/stock-entry")
    public ResponseEntity<ApiResponse<String>> addStockEntry(
            @PathVariable Long id,
            @RequestParam java.math.BigDecimal quantity,
            @RequestParam(required = false) java.math.BigDecimal rate,
            @RequestParam(required = false) String remarks) {

        return ResponseEntity.ok(productService.recordStockInward(id, quantity, rate, remarks));
    }

    // BUG-H15 fix: dedicated multipart endpoint for setting a product's
    // image against an already-existing product id, mirroring how a
    // PaymentProof is uploaded against an existing Payment id rather than
    // embedded in the create/update JSON body.
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/image")
    public ResponseEntity<ApiResponse<ProductResponse>> uploadProductImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {

        return ResponseEntity.ok(productService.uploadProductImage(id, file));
    }

    @GetMapping("/low-stock")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getLowStockProducts() {

        return ResponseEntity.ok(productService.getLowStockProducts());
    }

    // MRP-wise stock: Admin sees Company root stock, Super Stockist/
    // Distributor see only their own warehouse on-hand stock -- scoped
    // server-side in ProductService.getMrpWiseStock(), same as every other
    // stock view in this codebase.
    @GetMapping("/mrp-wise-stock")
    public ResponseEntity<ApiResponse<com.spartan.dms.dto.MrpWiseStockResponse>> getMrpWiseStock() {

        return ResponseEntity.ok(productService.getMrpWiseStock());
    }
}
