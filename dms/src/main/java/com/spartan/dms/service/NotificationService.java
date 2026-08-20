package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.NotificationResponse;
import com.spartan.dms.entity.Notification;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.repository.NotificationRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Recipient-scoped notification feed. A notification with recipientRole
 * null is the original global/admin feed (registrations, low stock, etc.);
 * one with recipientRole+recipientId set belongs to exactly one Super
 * Stockist or Distributor login (e.g. "your stock request was approved").
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int PAGE_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final SecurityUtils securityUtils;

    public ApiResponse<List<NotificationResponse>> getMyNotifications() {

        List<Notification> notifications;

        if (securityUtils.isAdmin()) {
            notifications = notificationRepository
                    .findByRecipientRoleIsNullOrderByCreatedAtDesc(PageRequest.of(0, PAGE_SIZE))
                    .getContent();
        } else if (securityUtils.isSuperStockist()) {
            Long id = securityUtils.getScopedSuperStockistId();
            notifications = notificationRepository
                    .findByRecipientRoleAndRecipientIdOrderByCreatedAtDesc(com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST, id, PageRequest.of(0, PAGE_SIZE))
                    .getContent();
        } else {
            Long id = securityUtils.getScopedDistributorId();
            notifications = notificationRepository
                    .findByRecipientRoleAndRecipientIdOrderByCreatedAtDesc(com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR, id, PageRequest.of(0, PAGE_SIZE))
                    .getContent();
        }

        List<NotificationResponse> responses = notifications.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<NotificationResponse>>builder()
                .success(true)
                .message("Notifications")
                .data(responses)
                .build();
    }

    public ApiResponse<Long> getMyUnreadCount() {

        long count;
        if (securityUtils.isAdmin()) {
            count = notificationRepository.countByRecipientRoleIsNullAndIsReadFalse();
        } else if (securityUtils.isSuperStockist()) {
            count = notificationRepository.countByRecipientRoleAndRecipientIdAndIsReadFalse(
                    com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST, securityUtils.getScopedSuperStockistId());
        } else {
            count = notificationRepository.countByRecipientRoleAndRecipientIdAndIsReadFalse(
                    com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR, securityUtils.getScopedDistributorId());
        }

        return ApiResponse.<Long>builder()
                .success(true)
                .message("Unread count")
                .data(count)
                .build();
    }

    public ApiResponse<String> markRead(Long id) {

        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        // Without this, any authenticated user could mark ANY notification
        // read by guessing/iterating ids -- including the global admin feed
        // or another Super Stockist's/Distributor's own notifications.
        // Mirrors the exact scoping getMyNotifications() already applies.
        assertOwnsNotification(notification);

        notification.setIsRead(true);
        notificationRepository.save(notification);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Marked as read")
                .data("OK")
                .build();
    }

    private void assertOwnsNotification(Notification notification) {
        if (securityUtils.isAdmin()) {
            if (notification.getRecipientRole() != null) {
                throw new com.spartan.dms.exception.ForbiddenException("Not authorized to access this notification");
            }
            return;
        }
        if (securityUtils.isSuperStockist()) {
            Long id = securityUtils.getScopedSuperStockistId();
            if (!com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST.equals(notification.getRecipientRole())
                    || !id.equals(notification.getRecipientId())) {
                throw new com.spartan.dms.exception.ForbiddenException("Not authorized to access this notification");
            }
            return;
        }
        Long id = securityUtils.getScopedDistributorId();
        if (!com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR.equals(notification.getRecipientRole())
                || !id.equals(notification.getRecipientId())) {
            throw new com.spartan.dms.exception.ForbiddenException("Not authorized to access this notification");
        }
    }

    public ApiResponse<String> markAllRead() {

        List<Notification> notifications;
        if (securityUtils.isAdmin()) {
            notifications = notificationRepository.findByRecipientRoleIsNullOrderByCreatedAtDesc(PageRequest.of(0, 500)).getContent();
        } else if (securityUtils.isSuperStockist()) {
            notifications = notificationRepository.findByRecipientRoleAndRecipientIdOrderByCreatedAtDesc(
                    com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST, securityUtils.getScopedSuperStockistId(), PageRequest.of(0, 500)).getContent();
        } else {
            notifications = notificationRepository.findByRecipientRoleAndRecipientIdOrderByCreatedAtDesc(
                    com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR, securityUtils.getScopedDistributorId(), PageRequest.of(0, 500)).getContent();
        }
        notifications.forEach(n -> n.setIsRead(true));
        notificationRepository.saveAll(notifications);

        return ApiResponse.<String>builder()
                .success(true)
                .message("All marked as read")
                .data("OK")
                .build();
    }

    /**
     * Helper other services call to emit a scoped notification without
     * each of them re-deriving the builder boilerplate. recipientRole is
     * "SUPER_STOCKIST" or "DISTRIBUTOR" (see SecurityUtils.ROLE_*); pass null for both to raise it on
     * the global/admin feed instead.
     */
    public void notify(String recipientRole, Long recipientId, String title, String message,
                        String notificationType, Long referenceId) {
        notificationRepository.save(Notification.builder()
                .title(title)
                .message(message)
                .notificationType(notificationType)
                .referenceId(referenceId)
                .recipientRole(recipientRole)
                .recipientId(recipientId)
                .isRead(false)
                .build());
    }

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .title(n.getTitle())
                .message(n.getMessage())
                .notificationType(n.getNotificationType())
                .referenceId(n.getReferenceId())
                .isRead(n.getIsRead())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
