package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.AssignDistributorsRequest;
import com.spartan.dms.dto.DistributorResponse;
import com.spartan.dms.dto.SuperStockistRequest;
import com.spartan.dms.dto.SuperStockistResponse;
import com.spartan.dms.service.SuperStockistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/super-stockists")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SuperStockistController {

    private final SuperStockistService superStockistService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<SuperStockistResponse>> createSuperStockist(
            @RequestBody SuperStockistRequest request) {

        return ResponseEntity.ok(superStockistService.createSuperStockist(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SuperStockistResponse>> updateSuperStockist(
            @PathVariable Long id,
            @RequestBody SuperStockistRequest request) {

        return ResponseEntity.ok(superStockistService.updateSuperStockist(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteSuperStockist(
            @PathVariable Long id) {

        return ResponseEntity.ok(superStockistService.deleteSuperStockist(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SuperStockistResponse>> getSuperStockistById(
            @PathVariable Long id) {

        return ResponseEntity.ok(superStockistService.getSuperStockistById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<SuperStockistResponse>>> getAllSuperStockists() {

        return ResponseEntity.ok(superStockistService.getAllSuperStockists());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<SuperStockistResponse>>> searchSuperStockist(
            @RequestParam String keyword) {

        return ResponseEntity.ok(superStockistService.searchSuperStockist(keyword));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updateSuperStockistStatus(
            @PathVariable Long id,
            @RequestParam Boolean status) {

        return ResponseEntity.ok(superStockistService.updateSuperStockistStatus(id, status));
    }

    // Sets the complete set of distributors assigned to this Super
    // Stockist. See AssignDistributorsRequest for the replace-set semantics.
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/assign-distributors")
    public ResponseEntity<ApiResponse<List<DistributorResponse>>> assignDistributors(
            @PathVariable Long id,
            @RequestBody AssignDistributorsRequest request) {

        return ResponseEntity.ok(superStockistService.assignDistributors(id, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST')")
    @GetMapping("/{id}/distributors")
    public ResponseEntity<ApiResponse<List<DistributorResponse>>> getAssignedDistributors(
            @PathVariable Long id) {

        return ResponseEntity.ok(superStockistService.getAssignedDistributors(id));
    }

    // ---- Self-service endpoints for the logged-in Super Stockist portal ----

    @PreAuthorize("hasRole('SUPER_STOCKIST')")
    @GetMapping("/me/profile")
    public ResponseEntity<ApiResponse<SuperStockistResponse>> getMyProfile() {

        return ResponseEntity.ok(superStockistService.getMyProfile());
    }

    @PreAuthorize("hasRole('SUPER_STOCKIST')")
    @GetMapping("/me/distributors")
    public ResponseEntity<ApiResponse<List<DistributorResponse>>> getMyAssignedDistributors() {

        return ResponseEntity.ok(superStockistService.getMyAssignedDistributors());
    }
}
