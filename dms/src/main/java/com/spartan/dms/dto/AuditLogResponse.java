package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {
    private Long id;
    private String action;
    private String entityType;
    private Long entityId;
    private String performedBy;
    private String performedByRole;
    private String details;
    private LocalDateTime createdAt;
}
