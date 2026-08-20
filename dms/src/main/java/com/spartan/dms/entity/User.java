package com.spartan.dms.entity;

import jakarta.persistence.*;
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
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "mobile_number", nullable = false, unique = true, length = 15)
    private String mobileNumber;

    @Column(name = "password", nullable = false)
    private String password;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "profile_image")
    private String profileImage;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    // ---- Forgot / reset password ----
    @Column(name = "reset_token", length = 255)
    private String resetToken;

    @Column(name = "reset_token_expiry")
    private java.time.LocalDateTime resetTokenExpiry;

    // Links a login account with role DISTRIBUTOR to the Distributor record
    // it should be scoped to. Null for ADMIN accounts. This is the field
    // every distributor-scoping check in the service layer keys off of.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    // Links a login account with role SUPER_STOCKIST to the SuperStockist
    // record it should be scoped to. Null for ADMIN and DISTRIBUTOR
    // accounts. SecurityUtils.getScopedSuperStockistId() keys off this.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id")
    private SuperStockist superStockist;

    // Self-registered distributor accounts start PENDING (see AuthService.register)
    // and cannot log in until an admin sets this to APPROVED (see AuthService.login).
    // Admin-created accounts (UserService.createUser) default to APPROVED.
    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private com.spartan.dms.enums.ApprovalStatus approvalStatus = com.spartan.dms.enums.ApprovalStatus.APPROVED;

    // Settings page (Page 12): persisted per-account so they follow the user
    // across devices/sessions, not just stored in browser localStorage.
    @Column(name = "theme_preference", length = 20)
    @Builder.Default
    private String themePreference = "light";

    @Column(name = "font_size_preference", length = 20)
    @Builder.Default
    private String fontSizePreference = "medium";

    // ---- Login brute-force protection (AuthService.login) ----
    // Consecutive bad-password attempts since the last successful login;
    // reset to 0 on success. At MAX_FAILED_LOGIN_ATTEMPTS, lockedUntil is
    // set and further attempts are rejected without even checking the
    // password until it passes.
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private java.time.LocalDateTime lockedUntil;

    // ---- BUG-M3: server-side logout / token invalidation ----
    // Nullable, no default -- null means "no restriction" so every existing
    // user behaves exactly as before until they use the new logout flow at
    // least once. Set to "now" on logout (see AuthService.logout()); any
    // JWT whose `iat` (issued-at) claim is before this timestamp is treated
    // as unauthenticated by JwtAuthenticationFilter, while a token issued
    // by a fresh login AFTER logout remains valid.
    @Column(name = "tokens_valid_since")
    private java.time.LocalDateTime tokensValidSince;
}