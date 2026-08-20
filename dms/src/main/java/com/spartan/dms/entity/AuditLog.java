package com.spartan.dms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_entity_type", columnList = "entity_type"),
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
})
public class AuditLog extends BaseEntity {

    // e.g. LOGIN, LOGOUT, CREATE, UPDATE, DELETE, APPROVE, REJECT
    @Column(name = "action", nullable = false, length = 30)
    private String action;

    // e.g. USER, DISTRIBUTOR, SHOP, INVOICE, PAYMENT, PRODUCT, SETTINGS, AUTH
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    // Username of whoever performed the action (or "anonymous" for a failed
    // login attempt where we don't yet trust the supplied username).
    @Column(name = "performed_by", length = 100)
    private String performedBy;

    @Column(name = "performed_by_role", length = 30)
    private String performedByRole;

    @Column(name = "details", length = 500)
    private String details;
}
