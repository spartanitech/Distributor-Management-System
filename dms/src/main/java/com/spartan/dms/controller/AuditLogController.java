package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.AuditLogResponse;
import com.spartan.dms.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>> getRecentLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "200") int limit) {

        return ResponseEntity.ok(auditLogService.getRecentLogs(entityType, limit));
    }
}
