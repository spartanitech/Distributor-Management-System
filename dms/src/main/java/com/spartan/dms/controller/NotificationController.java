package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.NotificationResponse;
import com.spartan.dms.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NotificationController {

    private final NotificationService notificationService;

    // Every role gets its own scoped feed — Admin sees the global feed,
    // a Super Stockist/Distributor sees only notifications addressed to them.
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getMyNotifications() {
        return ResponseEntity.ok(notificationService.getMyNotifications());
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Long>> getUnreadCount() {
        return ResponseEntity.ok(notificationService.getMyUnreadCount());
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<String>> markRead(@PathVariable Long id) {
        return ResponseEntity.ok(notificationService.markRead(id));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<String>> markAllRead() {
        return ResponseEntity.ok(notificationService.markAllRead());
    }
}
