package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.UserRequest;
import com.spartan.dms.dto.UserResponse;
import com.spartan.dms.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserController {

    private final UserService userService;

    // Self-service: any authenticated user (admin or distributor) updates
    // ONLY their own settings — identity comes from the security context,
    // never from a path/body id, so there is no way to edit someone else's.
    @PatchMapping("/me/settings")
    public ResponseEntity<ApiResponse<UserResponse>> updateOwnSettings(
            @RequestBody com.spartan.dms.dto.SettingsRequest request) {

        return ResponseEntity.ok(userService.updateOwnSettings(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @RequestBody UserRequest request) {

        return ResponseEntity.ok(userService.createUser(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long id,
            @RequestBody UserRequest request) {

        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteUser(
            @PathVariable Long id) {

        return ResponseEntity.ok(userService.deleteUser(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(
            @PathVariable Long id) {

        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers() {

        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<UserResponse>>> searchUser(
            @RequestParam String keyword) {

        return ResponseEntity.ok(userService.searchUser(keyword));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updateUserStatus(
            @PathVariable Long id,
            @RequestParam Boolean status) {

        return ResponseEntity.ok(userService.updateUserStatus(id, status));
    }

    // Approve a pending distributor registration. distributorId links the
    // login account to the Distributor record it should be scoped to (every
    // distributor-scoping check in the service layer keys off this link) —
    // required when approving a DISTRIBUTOR-role account.
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<UserResponse>> approveUser(
            @PathVariable Long id,
            @RequestParam(required = false) Long distributorId,
            @RequestParam(required = false) Long superStockistId) {

        return ResponseEntity.ok(userService.approveUser(id, distributorId, superStockistId));
    }

    // Backfills missing distributor / super stockist profiles for accounts
    // created before auto-provisioning existed. Idempotent -- running it
    // twice is a no-op.
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/repair-profiles")
    public ResponseEntity<ApiResponse<List<String>>> repairMissingProfiles() {

        return ResponseEntity.ok(userService.repairMissingProfiles());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<UserResponse>> rejectUser(
            @PathVariable Long id) {

        return ResponseEntity.ok(userService.rejectUser(id));
    }

    // BUG-H10 fix: the new password used to arrive as a ?password= query
    // parameter (logged in plaintext by servers/proxies/browser history).
    // It now travels in the JSON request body instead; URL path and method
    // are unchanged.
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/reset-password")
    public ResponseEntity<ApiResponse<String>> resetPassword(
            @PathVariable Long id,
            @RequestBody com.spartan.dms.dto.ResetPasswordByAdminRequest request) {

        return ResponseEntity.ok(userService.resetPassword(id, request.getPassword()));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponse>> getProfile() {

        return ResponseEntity.ok(userService.getProfile());
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @RequestBody UserRequest request) {

        return ResponseEntity.ok(userService.updateProfile(request));
    }
}