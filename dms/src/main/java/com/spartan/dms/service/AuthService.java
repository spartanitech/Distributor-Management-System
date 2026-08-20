package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.ForgotPasswordRequest;
import com.spartan.dms.dto.LoginRequest;
import com.spartan.dms.dto.LoginResponse;
import com.spartan.dms.dto.RegisterRequest;
import com.spartan.dms.dto.ResetPasswordRequest;
import com.spartan.dms.entity.Role;
import com.spartan.dms.entity.User;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.DuplicateResourceException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.repository.RoleRepository;
import com.spartan.dms.repository.UserRepository;
import com.spartan.dms.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final com.spartan.dms.repository.NotificationRepository notificationRepository;
    private final AuditLogService auditLogService;

    @Value("${app.frontend-base-url:http://localhost:8080}")
    private String frontendBaseUrl;

    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    // ---- BUG-M9: lightweight in-process rate limiting for forgot-password ----
    // Mirrors the style of the login lockout above (simple in-memory
    // counters, no new infra) rather than a full architecture change. Keyed
    // by email since that's the only identifier available on
    // ForgotPasswordRequest; per-key attempt timestamps within the last
    // FORGOT_PASSWORD_WINDOW_MINUTES are tracked and once the cap is hit,
    // further requests silently skip sending the email while still
    // returning the SAME generic success message as always -- revealing
    // that rate limiting kicked in would itself be an information leak.
    private static final int MAX_FORGOT_PASSWORD_ATTEMPTS = 3;
    private static final int FORGOT_PASSWORD_WINDOW_MINUTES = 15;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<LocalDateTime>>
            forgotPasswordAttempts = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean allowForgotPasswordAttempt(String email) {
        if (email == null) {
            return true;
        }
        String key = email.trim().toLowerCase();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(FORGOT_PASSWORD_WINDOW_MINUTES);

        java.util.concurrent.ConcurrentLinkedDeque<LocalDateTime> timestamps =
                forgotPasswordAttempts.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_FORGOT_PASSWORD_ATTEMPTS) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * Handles the public self-service sign-up form on the frontend
     * (POST /api/v1/auth/register).
     *
     * SECURITY: this used to grant every self-registered account the ADMIN
     * role and log them straight in — meaning anyone on the internet could
     * mint themselves a full-control admin account. Public sign-up now:
     *   - never grants ADMIN (that can only be done by an existing admin
     *     via POST /api/v1/users)
     *   - creates the account inactive (active=false) so an existing admin
     *     must approve/activate it via PATCH /api/v1/users/{id}/status
     *     before it can log in
     *   - does NOT return a token, since the account isn't usable yet
     */
    // BUG-L1 fix: all three duplicate-field checks below used to throw
    // distinct messages ("Username already exists" / "Email already
    // exists" / "Mobile Number already exists"), which let an attacker
    // enumerate which usernames/emails/mobile numbers are already
    // registered just by probing this endpoint. They now all throw the
    // same generic message.
    private static final String REGISTRATION_FAILED_MESSAGE =
            "Registration failed. Please check your details and try again, or contact support if you believe this is an error.";

    public ApiResponse<String> register(RegisterRequest request) {

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException(REGISTRATION_FAILED_MESSAGE);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException(REGISTRATION_FAILED_MESSAGE);
        }
        if (userRepository.existsByMobileNumber(request.getPhone())) {
            throw new DuplicateResourceException(REGISTRATION_FAILED_MESSAGE);
        }

        boolean isSuperStockistRequest = "SUPER_STOCKIST".equalsIgnoreCase(request.getRequestedRole());
        String roleName = isSuperStockistRequest ? "SUPER_STOCKIST" : "DISTRIBUTOR";

        Role role = roleRepository.findByRoleName(roleName)
                .orElseGet(() -> roleRepository.save(
                        Role.builder()
                                .roleName(roleName)
                                .description(isSuperStockistRequest
                                        ? "Super Stockist portal login"
                                        : "Distributor portal login")
                                .active(true)
                                .build()));

        User user = User.builder()
                .fullName(request.getFullName())
                .username(request.getUsername())
                .email(request.getEmail())
                .mobileNumber(request.getPhone())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .active(false)
                .approvalStatus(com.spartan.dms.enums.ApprovalStatus.PENDING)
                .build();

        user = userRepository.save(user);

        notificationRepository.save(com.spartan.dms.entity.Notification.builder()
                .title(isSuperStockistRequest ? "New Super Stockist registration" : "New distributor registration")
                .message(request.getFullName() + " (" + request.getUsername() + ") requested a "
                        + (isSuperStockistRequest ? "Super Stockist" : "distributor")
                        + " account and is awaiting approval.")
                .notificationType(isSuperStockistRequest
                        ? com.spartan.dms.enums.NotificationType.SUPER_STOCKIST.name()
                        : com.spartan.dms.enums.NotificationType.DISTRIBUTOR.name())
                .referenceId(user.getId())
                .isRead(false)
                .build());

        auditLogService.logAs("REGISTER", "USER", user.getId(), user.getUsername(), roleName, "Self-registration, pending approval");

        return ApiResponse.<String>builder()
                .success(true)
                .message("Account request submitted. An administrator must approve and link your account to a "
                        + (isSuperStockistRequest ? "Super Stockist" : "distributor")
                        + " record before you can sign in.")
                .data(null)
                .build();
    }

    public ApiResponse<LoginResponse> login(LoginRequest request) {

        // Fetched up front (not just after a successful authenticate(), as
        // this used to) so a lockout can be enforced before even checking
        // the password, and so a failed attempt has a row to record itself
        // against.
        User user = userRepository.findByUsername(request.getUsername()).orElse(null);

        if (user != null && user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            auditLogService.logAs("LOGIN_BLOCKED", "AUTH", user.getId(), user.getUsername(),
                    user.getRole() != null ? user.getRole().getRoleName() : null, "Account temporarily locked (too many failed attempts)");
            long minutesLeft = java.time.Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMinutes() + 1;
            throw new BadRequestException("Too many failed login attempts. Try again in " + minutesLeft + " minute(s).");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );
        } catch (Exception e) {
            // Brute-force protection: without this, POST /auth/login has no
            // limit at all on password guesses per second. Counted against
            // the account rather than the caller's IP (simpler, and this
            // API sits behind arbitrary proxies/NATs where a client IP
            // isn't reliably attributable) -- a genuine owner locked out by
            // an attacker's guesses can still reset their password via
            // forgot-password, which isn't gated by this counter.
            if (user != null) {
                int attempts = (user.getFailedLoginAttempts() == null ? 0 : user.getFailedLoginAttempts()) + 1;
                user.setFailedLoginAttempts(attempts);
                if (attempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
                    user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
                }
                userRepository.save(user);
            }
            auditLogService.logAs("LOGIN_FAILED", "AUTH", null, request.getUsername(), null, "Bad credentials");
            throw e;
        }

        if (user == null) {
            // authenticationManager.authenticate() succeeded, which means
            // CustomUserDetailsService found this username -- this branch
            // should be unreachable, but fail closed rather than NPE below.
            throw new ResourceNotFoundException("Invalid Username or Password");
        }

        if (user.getFailedLoginAttempts() != null && user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        if (user.getApprovalStatus() == com.spartan.dms.enums.ApprovalStatus.REJECTED) {
            auditLogService.logAs("LOGIN_BLOCKED", "AUTH", user.getId(), user.getUsername(), user.getRole().getRoleName(), "Account rejected");
            throw new BadRequestException("Your distributor account request was rejected. Contact your administrator.");
        }
        if (user.getApprovalStatus() == com.spartan.dms.enums.ApprovalStatus.PENDING
                || Boolean.FALSE.equals(user.getActive())) {
            auditLogService.logAs("LOGIN_BLOCKED", "AUTH", user.getId(), user.getUsername(), user.getRole().getRoleName(), "Account pending approval / inactive");
            throw new BadRequestException("This account is awaiting administrator approval");
        }

        String token = jwtUtil.generateToken(user.getUsername());

        auditLogService.logAs("LOGIN", "AUTH", user.getId(), user.getUsername(), user.getRole().getRoleName(), "Login successful");

        LoginResponse.LoginResponseBuilder responseBuilder = LoginResponse.builder()
                .userId(user.getId())
                .fullName(user.getFullName())
                .token(token)
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole().getRoleName())
                .themePreference(user.getThemePreference())
                .fontSizePreference(user.getFontSizePreference())
                .profileImage(user.getProfileImage());

        if (user.getDistributor() != null) {
            responseBuilder
                    .distributorId(user.getDistributor().getId())
                    .distributorName(user.getDistributor().getDistributorName());
        }

        if (user.getSuperStockist() != null) {
            responseBuilder
                    .superStockistId(user.getSuperStockist().getId())
                    .superStockistName(user.getSuperStockist().getSuperStockistName());
        }

        return ApiResponse.<LoginResponse>builder()
                .success(true)
                .message("Login Successful")
                .data(responseBuilder.build())
                .build();
    }

    /**
     * Generates a one-time reset token (valid 30 minutes), stores it on the
     * user, and emails a reset link. Always returns a generic success
     * message regardless of whether the email exists, so this endpoint
     * can't be used to enumerate registered accounts.
     */
    @Transactional
    public ApiResponse<String> forgotPassword(ForgotPasswordRequest request) {

        // BUG-M9: cap attempts per email before doing any work. Deliberately
        // still returns the normal generic success message below either way
        // -- an attacker probing this endpoint must not be able to tell
        // "rate limited" apart from "email sent" / "email doesn't exist".
        if (allowForgotPasswordAttempt(request.getEmail())) {

            userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
                String rawToken = UUID.randomUUID().toString();

                // BUG-L13 fix: store the reset token bcrypt-hashed, never in
                // plaintext -- the RAW token is still what's emailed to the
                // user (unchanged), only the persisted copy changes.
                user.setResetToken(passwordEncoder.encode(rawToken));
                user.setResetTokenExpiry(LocalDateTime.now().plusMinutes(30));
                userRepository.save(user);

                String resetLink = frontendBaseUrl + "/index.html?resetToken=" + rawToken;
                mailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), resetLink);
            });
        }

        return ApiResponse.<String>builder()
                .success(true)
                .message("If an account exists for that email, a password reset link has been sent.")
                .data(null)
                .build();
    }

    /**
     * Consumes a reset token issued by forgotPassword(): validates it
     * exists and hasn't expired, sets the new (encoded) password, and
     * invalidates the token so it can't be reused.
     */
    @Transactional
    public ApiResponse<String> resetPassword(ResetPasswordRequest request) {

        // BUG-L13 fix: resetToken is now stored bcrypt-hashed, so it can't
        // be looked up by direct equality against the raw token from the
        // link anymore (a fresh bcrypt hash of the same input differs every
        // time). Instead we pull the small set of still-active (non-null,
        // non-expired) candidates and check each with
        // PasswordEncoder.matches() -- there are only ever a handful of
        // these outstanding at once in practice.
        User user = userRepository.findByResetTokenIsNotNullAndResetTokenExpiryAfter(LocalDateTime.now())
                .stream()
                .filter(candidate -> passwordEncoder.matches(request.getToken(), candidate.getResetToken()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Invalid or expired reset link"));

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setResetToken(null);
        user.setResetTokenExpiry(null);
        userRepository.save(user);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Password reset successfully. Please sign in with your new password.")
                .data(null)
                .build();
    }

    // BUG-M3 fix: actual server-side token invalidation on logout. Setting
    // tokensValidSince to "now" means every JWT issued before this instant
    // (checked via its `iat` claim in JwtAuthenticationFilter) is rejected
    // from this point on, while a token from a fresh login AFTER this call
    // remains valid -- the semantics a real logout should have. Called
    // from AuthController.logout() ALONGSIDE the existing audit-log-only
    // behavior there, not instead of it.
    @Transactional
    public void logout(User user) {
        user.setTokensValidSince(LocalDateTime.now());
        userRepository.save(user);
    }
}