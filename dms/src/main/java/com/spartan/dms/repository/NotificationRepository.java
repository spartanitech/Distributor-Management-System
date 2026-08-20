package com.spartan.dms.repository;

import com.spartan.dms.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByIsReadFalse();

    List<Notification> findByNotificationType(String notificationType);

    /* ---------- Recipient scoping ---------- */
    // Admin/global feed: notifications with no specific recipient.

    org.springframework.data.domain.Page<Notification> findByRecipientRoleIsNullOrderByCreatedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    long countByRecipientRoleIsNullAndIsReadFalse();

    org.springframework.data.domain.Page<Notification> findByRecipientRoleAndRecipientIdOrderByCreatedAtDesc(
            String recipientRole, Long recipientId, org.springframework.data.domain.Pageable pageable);

    long countByRecipientRoleAndRecipientIdAndIsReadFalse(String recipientRole, Long recipientId);
}