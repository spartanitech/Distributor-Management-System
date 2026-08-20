package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.AuditLogResponse;
import com.spartan.dms.entity.AuditLog;
import com.spartan.dms.repository.AuditLogRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final SecurityUtils securityUtils;

    /**
     * Logs an action performed by the CURRENTLY authenticated user (the
     * common case — creating/updating/deleting something while logged in).
     * Never throws: a failure to write an audit row must never break the
     * actual business operation it's describing.
     */
    public void log(String action, String entityType, Long entityId, String details) {
        try {
            var user = securityUtils.getCurrentUser();
            record(action, entityType, entityId, user.getUsername(),
                    user.getRole() != null ? user.getRole().getRoleName() : null, details);
        } catch (Exception e) {
            log.warn("Audit log skipped (no authenticated context) for {} {}: {}", action, entityType, e.getMessage());
        }
    }

    /**
     * Logs an action where the actor isn't (yet) the authenticated
     * SecurityContext principal — e.g. a login attempt (success or
     * failure), where we log the *supplied* username directly.
     */
    public void logAs(String action, String entityType, Long entityId,
                       String performedBy, String performedByRole, String details) {
        record(action, entityType, entityId, performedBy, performedByRole, details);
    }

    private void record(String action, String entityType, Long entityId,
                         String performedBy, String performedByRole, String details) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .performedBy(performedBy)
                    .performedByRole(performedByRole)
                    .details(details)
                    .build());
        } catch (Exception e) {
            log.error("Failed to write audit log entry: {}", e.getMessage());
        }
    }

    public ApiResponse<List<AuditLogResponse>> getRecentLogs(String entityType, int limit) {

        int size = limit > 0 ? limit : 100;

        List<AuditLog> logs = (entityType != null && !entityType.isBlank())
                ? auditLogRepository.findByEntityTypeOrderByCreatedAtDesc(entityType.toUpperCase(), PageRequest.of(0, size))
                : auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, size));

        List<AuditLogResponse> response = logs.stream()
                .map(a -> AuditLogResponse.builder()
                        .id(a.getId())
                        .action(a.getAction())
                        .entityType(a.getEntityType())
                        .entityId(a.getEntityId())
                        .performedBy(a.getPerformedBy())
                        .performedByRole(a.getPerformedByRole())
                        .details(a.getDetails())
                        .createdAt(a.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return ApiResponse.<List<AuditLogResponse>>builder()
                .success(true)
                .message("Audit Log")
                .data(response)
                .build();
    }
}
