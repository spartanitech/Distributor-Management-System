package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.DistributorRequest;
import com.spartan.dms.dto.DistributorResponse;
import com.spartan.dms.service.DistributorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/distributors")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DistributorController {

    private final DistributorService distributorService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<DistributorResponse>> createDistributor(
            @RequestBody DistributorRequest request) {

        return ResponseEntity.ok(distributorService.createDistributor(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DistributorResponse>> updateDistributor(
            @PathVariable Long id,
            @RequestBody DistributorRequest request) {

        return ResponseEntity.ok(distributorService.updateDistributor(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteDistributor(
            @PathVariable Long id) {

        return ResponseEntity.ok(distributorService.deleteDistributor(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DistributorResponse>> getDistributorById(
            @PathVariable Long id) {

        return ResponseEntity.ok(distributorService.getDistributorById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<DistributorResponse>>> getAllDistributors() {

        return ResponseEntity.ok(distributorService.getAllDistributors());
    }

    // Data-quality check: distributors with no Super Stockist assigned
    // (legacy rows from before the hierarchy was enforced) so an admin can
    // find and fix them instead of them silently showing as "Unassigned"
    // throughout reports.
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/unassigned")
    public ResponseEntity<ApiResponse<List<DistributorResponse>>> getUnassignedDistributors() {

        return ResponseEntity.ok(distributorService.getUnassignedDistributors());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<DistributorResponse>>> searchDistributor(
            @RequestParam String keyword) {

        return ResponseEntity.ok(distributorService.searchDistributor(keyword));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updateDistributorStatus(
            @PathVariable Long id,
            @RequestParam Boolean status) {

        return ResponseEntity.ok(distributorService.updateDistributorStatus(id, status));
    }
}