package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.WarehouseResponse;
import com.spartan.dms.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/warehouse")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WarehouseController {

    private final WarehouseService warehouseService;

    // Own stock: Admin gets everything, a Super Stockist gets their own
    // warehouse rows, a Distributor gets their own.
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<WarehouseResponse>>> getMyWarehouse() {
        return ResponseEntity.ok(warehouseService.getMyWarehouse());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/company")
    public ResponseEntity<ApiResponse<List<WarehouseResponse>>> getCompanyStock() {
        return ResponseEntity.ok(warehouseService.getCompanyStock());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @GetMapping("/super-stockist/{id}")
    public ResponseEntity<ApiResponse<List<WarehouseResponse>>> getSuperStockistWarehouse(
            @PathVariable Long id) {
        return ResponseEntity.ok(warehouseService.getSuperStockistWarehouse(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping("/distributor/{id}")
    public ResponseEntity<ApiResponse<List<WarehouseResponse>>> getDistributorWarehouse(
            @PathVariable Long id) {
        return ResponseEntity.ok(warehouseService.getDistributorWarehouse(id));
    }
}
