package com.spartan.dms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "notification_type", length = 50)
    private String notificationType;

    @Column(name = "reference_id")
    private Long referenceId;

    // Who this notification is for. Both null (the original behavior) =
    // admin/global feed. Otherwise scoped to one Super Stockist or
    // Distributor login — e.g. "ROLE_SUPER_STOCKIST" + that SS's id.
    @Column(name = "recipient_role", length = 30)
    private String recipientRole;

    @Column(name = "recipient_id")
    private Long recipientId;

    @Column(name = "is_read")
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "active")
    @Builder.Default
    private Boolean active = true;
}