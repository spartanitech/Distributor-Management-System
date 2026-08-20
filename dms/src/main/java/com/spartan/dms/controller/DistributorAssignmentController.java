package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.DistributorAssignmentActionDto;
import com.spartan.dms.dto.DistributorAssignmentRequest;
import com.spartan.dms.dto.DistributorAssignmentResponse;
import com.spartan.dms.enums.AssignmentStatus;
import com.spartan.dms.service.DistributorAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/distributor-assignments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DistributorAssignmentController {

    private final DistributorAssignmentService assignmentService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<DistributorAssignmentResponse>> createAssignment(
            @RequestBody DistributorAssignmentRequest request) {
        return ResponseEntity.ok(assignmentService.createAssignment(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<DistributorAssignmentResponse>>> getAssignments(
            @RequestParam(required = false) AssignmentStatus status) {
        return ResponseEntity.ok(assignmentService.getAssignments(status));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DistributorAssignmentResponse>> getAssignmentById(
            @PathVariable Long id) {
        return ResponseEntity.ok(assignmentService.getAssignmentById(id));
    }

    @PreAuthorize("hasRole('DISTRIBUTOR')")
    @PutMapping("/{id}/respond")
    public ResponseEntity<ApiResponse<DistributorAssignmentResponse>> respondToAssignment(
            @PathVariable Long id,
            @RequestBody DistributorAssignmentActionDto dto) {
        return ResponseEntity.ok(assignmentService.respondToAssignment(id, dto));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteAssignment(
            @PathVariable Long id) {
        return ResponseEntity.ok(assignmentService.deleteAssignment(id));
    }
}
