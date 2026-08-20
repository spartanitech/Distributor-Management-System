package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.UserRequest;
import com.spartan.dms.dto.UserResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Role;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.entity.User;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.DuplicateResourceException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.UserMapper;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.RoleRepository;
import com.spartan.dms.repository.SuperStockistRepository;
import com.spartan.dms.repository.UserRepository;
import com.spartan.dms.security.SecurityUtils;
import com.spartan.dms.util.PasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DistributorRepository distributorRepository;
    private final SuperStockistRepository superStockistRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecurityUtils securityUtils;
    private final AuditLogService auditLogService;

    // Writes users AND (auto-provisions) distributors/super_stockists, so
    // both must land or neither -- otherwise a failure partway through
    // leaves an orphan profile with no login attached to it.
    @Transactional
    public ApiResponse<UserResponse> createUser(UserRequest request) {

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already exists");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already exists");
        }

        if (userRepository.existsByMobileNumber(request.getMobileNumber())) {
            throw new DuplicateResourceException("Mobile Number already exists");
        }

        // No @Valid on the controller (deliberately -- see updateUser/
        // updateProfile, which reuse this same DTO with an OPTIONAL
        // password, so a blanket @NotBlank/@Size wouldn't fit both call
        // sites).
        //
        // BUG-L9 fix: PasswordGenerator was previously a correctly-written
        // but entirely dead utility class -- nothing in the codebase ever
        // called it. Wiring it in here is small and backward-compatible:
        // every existing caller that already supplies a password is
        // completely unaffected (that value is still used as-is, still
        // subject to the same strength check as before). The only
        // behavior change is for the previously-error case of a
        // null/blank password, which is now a genuinely useful workflow
        // for an admin creating a Distributor/Super Stockist login on
        // someone else's behalf: instead of failing, a strong
        // SecureRandom-generated password is used, and it's handed back
        // once via the response message (never stored/logged in the
        // clear anywhere) so the admin can pass it on.
        String effectivePassword = request.getPassword();
        String generatedPassword = null;
        if (effectivePassword == null || effectivePassword.isBlank()) {
            generatedPassword = PasswordGenerator.generatePassword(12);
            effectivePassword = generatedPassword;
        }
        validatePasswordStrength(effectivePassword);

        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        User user = userMapper.toEntity(request);
        user.setRole(role);
        user.setPassword(passwordEncoder.encode(effectivePassword));
        // Admin-created accounts are immediately usable — no approval queue.
        // (Builder.Default's APPROVED initializer only applies when
        // constructed via User.builder(); userMapper.toEntity() goes through
        // ModelMapper's no-arg constructor + reflection, which does NOT run
        // it, so this must be set explicitly or the column would get NULL.)
        user.setApprovalStatus(com.spartan.dms.enums.ApprovalStatus.APPROVED);

        // A DISTRIBUTOR/SUPER_STOCKIST login must be linked to its profile
        // record — that link is what every scoping check in the service
        // layer keys off of, so an unlinked account would log in fine and
        // then 403 on every scoped action.
        //
        // The link is now established automatically: if the admin supplies
        // an explicit id we attach to that existing record, otherwise we
        // CREATE the matching profile from this user's own details, so a
        // single "create user" action always produces a usable, fully
        // linked account and the Distributor/Super Stockist page shows it
        // immediately. Name/contact/mobile are copied from the User, so
        // the two records can never disagree about who this is.
        if (com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR.equalsIgnoreCase(role.getRoleName())) {
            Distributor distributor = request.getDistributorId() != null
                    ? distributorRepository.findById(request.getDistributorId())
                            .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"))
                    : findOrCreateDistributorFor(request);
            user.setDistributor(distributor);
            user.setSuperStockist(null);
        } else if (com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST.equalsIgnoreCase(role.getRoleName())) {
            SuperStockist superStockist = request.getSuperStockistId() != null
                    ? superStockistRepository.findById(request.getSuperStockistId())
                            .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"))
                    : findOrCreateSuperStockistFor(request);
            user.setSuperStockist(superStockist);
            user.setDistributor(null);
        } else {
            user.setDistributor(null);
            user.setSuperStockist(null);
        }

        user = userRepository.save(user);

        String message = generatedPassword != null
                ? "User Created Successfully. Generated password: " + generatedPassword
                        + " (shown once here — share it with the user securely; it cannot be retrieved again)"
                : "User Created Successfully";

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message(message)
                .data(userMapper.toResponse(user))
                .build();
    }

    public ApiResponse<List<UserResponse>> getAllUsers() {

        List<UserResponse> users = userRepository.findAll()
                .stream()
                .map(userMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<UserResponse>>builder()
                .success(true)
                .message("User List")
                .data(users)
                .build();
    }

    public ApiResponse<UserResponse> getUserById(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("User Details")
                .data(userMapper.toResponse(user))
                .build();
    }

    public ApiResponse<UserResponse> updateUser(Long id, UserRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (request.getUsername() != null
                && userRepository.existsByUsernameAndIdNot(request.getUsername(), id)) {
            throw new com.spartan.dms.exception.DuplicateResourceException("Username already exists");
        }
        if (request.getEmail() != null
                && userRepository.existsByEmailAndIdNot(request.getEmail(), id)) {
            throw new com.spartan.dms.exception.DuplicateResourceException("Email already exists");
        }
        if (request.getMobileNumber() != null
                && userRepository.existsByMobileNumberAndIdNot(request.getMobileNumber(), id)) {
            throw new com.spartan.dms.exception.DuplicateResourceException("Mobile Number already exists");
        }

        userMapper.updateEntity(request, user);

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            validatePasswordStrength(request.getPassword());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        if (request.getRoleId() != null) {
            Role role = roleRepository.findById(request.getRoleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

            boolean wasAdmin = user.getRole() != null
                    && com.spartan.dms.security.SecurityUtils.ROLE_ADMIN.equalsIgnoreCase(user.getRole().getRoleName());
            boolean stillAdmin = com.spartan.dms.security.SecurityUtils.ROLE_ADMIN.equalsIgnoreCase(role.getRoleName());
            if (wasAdmin && !stillAdmin) {
                guardAgainstLastAdminLockout(user, "change this account's role away from ADMIN");
            }

            user.setRole(role);

            if (com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR.equalsIgnoreCase(role.getRoleName())) {
                if (request.getDistributorId() == null) {
                    throw new BadRequestException("distributorId is required for a DISTRIBUTOR user");
                }
                Distributor distributor = distributorRepository.findById(request.getDistributorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
                user.setDistributor(distributor);
                user.setSuperStockist(null);
            } else if (com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST.equalsIgnoreCase(role.getRoleName())) {
                if (request.getSuperStockistId() == null) {
                    throw new BadRequestException("superStockistId is required for a SUPER_STOCKIST user");
                }
                SuperStockist superStockist = superStockistRepository.findById(request.getSuperStockistId())
                        .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));
                user.setSuperStockist(superStockist);
                user.setDistributor(null);
            } else {
                user.setDistributor(null);
                user.setSuperStockist(null);
            }
        }

        user = userRepository.save(user);

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("User Updated Successfully")
                .data(userMapper.toResponse(user))
                .build();
    }

    public ApiResponse<String> deleteUser(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        guardAgainstLastAdminLockout(user, "delete this account");

        userRepository.delete(user);

        return ApiResponse.<String>builder()
                .success(true)
                .message("User Deleted Successfully")
                .data("Deleted")
                .build();
    }

    /**
     * Auto-provisions the Distributor profile that backs a new
     * DISTRIBUTOR login, so admin creates ONE thing instead of two and
     * the records can never drift apart or be left orphaned.
     *
     * mobile_number is unique on the distributors table, so if a profile
     * with this mobile already exists we link to it rather than blindly
     * inserting a duplicate (which would fail on the constraint anyway) —
     * that also covers the common case of an admin creating the profile
     * first and the login afterwards.
     *
     * The Super Stockist assignment is deliberately NOT set here: it's
     * assigned separately via SuperStockistService.assignDistributors(),
     * and DistributorService.getUnassignedDistributors() surfaces anyone
     * still waiting.
     */
    /**
     * One-shot repair for accounts that carry a DISTRIBUTOR or
     * SUPER_STOCKIST role but have no linked profile row.
     *
     * createUser() and approveUser() both auto-provision the profile now,
     * so no NEW account can end up in this state. But accounts created
     * before that existed still can -- they hold the role, log in fine,
     * and then 403 on every scoped action while being invisible on the
     * Distributors / Super Stockists page, so an admin can't assign them
     * anything. Fixing those one at a time by re-saving each user is
     * tedious and easy to miss; this sweeps them in one pass using the
     * exact same findOrCreate* helpers, so a repaired account is
     * indistinguishable from a freshly approved one.
     *
     * Safe to run repeatedly: accounts that already have a profile are
     * skipped, and the helpers match on mobile number before creating,
     * so an existing profile is linked rather than duplicated.
     */
    @Transactional
    public ApiResponse<List<String>> repairMissingProfiles() {

        securityUtils.assertAdmin();

        List<String> repaired = new java.util.ArrayList<>();

        for (User user : userRepository.findAll()) {
            if (user.getRole() == null) {
                continue;
            }
            String roleName = user.getRole().getRoleName();

            if (com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR.equalsIgnoreCase(roleName)
                    && user.getDistributor() == null) {
                user.setDistributor(findOrCreateDistributorFor(userAsRequest(user)));
                userRepository.save(user);
                repaired.add(user.getUsername() + " -> distributor profile created");

            } else if (com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST.equalsIgnoreCase(roleName)
                    && user.getSuperStockist() == null) {
                user.setSuperStockist(findOrCreateSuperStockistFor(userAsRequest(user)));
                userRepository.save(user);
                repaired.add(user.getUsername() + " -> super stockist profile created");
            }
        }

        auditLogService.log("REPAIR", "USER", null,
                "Backfilled " + repaired.size() + " missing distributor/super stockist profile(s)");

        return ApiResponse.<List<String>>builder()
                .success(true)
                .message(repaired.isEmpty()
                        ? "All accounts already have their profile linked — nothing to repair."
                        : repaired.size() + " account(s) repaired and now visible for assignment")
                .data(repaired)
                .build();
    }

    private Distributor findOrCreateDistributorFor(UserRequest request) {
        return distributorRepository.findByMobileNumber(request.getMobileNumber())
                .orElseGet(() -> distributorRepository.save(Distributor.builder()
                        .distributorName(request.getFullName())
                        .contactPerson(request.getFullName())
                        .mobileNumber(request.getMobileNumber())
                        .email(request.getEmail())
                        .active(true)
                        .build()));
    }

    // findOrCreate*For() take a UserRequest because their main caller is
    // createUser(); approveUser() works from an already-persisted User, so
    // this adapts one to the other rather than duplicating the logic.
    private UserRequest userAsRequest(User user) {
        UserRequest r = new UserRequest();
        r.setFullName(user.getFullName());
        r.setEmail(user.getEmail());
        r.setMobileNumber(user.getMobileNumber());
        return r;
    }

    /** Super Stockist counterpart of findOrCreateDistributorFor(). */
    private SuperStockist findOrCreateSuperStockistFor(UserRequest request) {
        return superStockistRepository.findByMobileNumber(request.getMobileNumber())
                .orElseGet(() -> superStockistRepository.save(SuperStockist.builder()
                        .superStockistName(request.getFullName())
                        .contactPerson(request.getFullName())
                        .mobileNumber(request.getMobileNumber())
                        .email(request.getEmail())
                        .active(true)
                        .build()));
    }

    // Refuses an action that would leave the system with zero usable admin
    // logins: deleting/deactivating/demoting your own account (self-lockout
    // -- you'd immediately lose the ability to undo it), or the very last
    // remaining active ADMIN account (system-wide lockout -- self-registration
    // never grants ADMIN, per AuthService.register, so there'd be no way
    // back in for anyone).
    private void guardAgainstLastAdminLockout(User targetUser, String actionDescription) {
        boolean isAdminAccount = targetUser.getRole() != null
                && com.spartan.dms.security.SecurityUtils.ROLE_ADMIN.equalsIgnoreCase(targetUser.getRole().getRoleName());
        if (!isAdminAccount) {
            return;
        }

        User currentUser = securityUtils.getCurrentUser();
        if (currentUser != null && currentUser.getId() != null && currentUser.getId().equals(targetUser.getId())) {
            throw new BadRequestException("You cannot " + actionDescription + " while logged in as it — ask another admin to do this.");
        }

        long activeAdmins = userRepository.countByRole_RoleNameAndActiveTrue(
                com.spartan.dms.security.SecurityUtils.ROLE_ADMIN);
        if (Boolean.TRUE.equals(targetUser.getActive()) && activeAdmins <= 1) {
            throw new BadRequestException("Cannot " + actionDescription + " — it is the last active admin account.");
        }
    }

    public ApiResponse<List<UserResponse>> searchUser(String keyword) {

        List<UserResponse> users = userRepository.findAll()
                .stream()
                .filter(user ->
                        (user.getFullName() != null &&
                                user.getFullName().toLowerCase().contains(keyword.toLowerCase()))
                                || (user.getUsername() != null &&
                                user.getUsername().toLowerCase().contains(keyword.toLowerCase())))
                .map(userMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<UserResponse>>builder()
                .success(true)
                .message("Search Result")
                .data(users)
                .build();
    }

    public ApiResponse<String> updateUserStatus(Long id, Boolean status) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (Boolean.FALSE.equals(status)) {
            guardAgainstLastAdminLockout(user, "deactivate this account");
        }

        user.setActive(status);

        userRepository.save(user);

        return ApiResponse.<String>builder()
                .success(true)
                .message("User Status Updated Successfully")
                .data("Success")
                .build();
    }

    // Approves a pending distributor registration (see AuthService.register).
    // distributorId links this login to the Distributor record every
    // distributor-scoping check in the service layer keys off of — required
    // when the account being approved has the DISTRIBUTOR role.
    @Transactional
    public ApiResponse<UserResponse> approveUser(Long id, Long distributorId, Long superStockistId) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean isDistributorRole = user.getRole() != null
                && com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR.equalsIgnoreCase(user.getRole().getRoleName());
        boolean isSuperStockistRole = user.getRole() != null
                && com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST.equalsIgnoreCase(user.getRole().getRoleName());

        // Same auto-provisioning as createUser(): an explicit id attaches to
        // that existing profile, otherwise the profile is created (or
        // matched by mobile) from the account's own details. Approving a
        // self-registered Super Stockist/Distributor therefore always
        // yields a usable, fully linked account and an immediately visible
        // row on the corresponding page -- it can no longer be approved
        // into a half-working state that 403s on every scoped action.
        if (isDistributorRole) {
            if (distributorId != null) {
                Distributor distributor = distributorRepository.findById(distributorId)
                        .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
                user.setDistributor(distributor);
            } else if (user.getDistributor() == null) {
                user.setDistributor(findOrCreateDistributorFor(userAsRequest(user)));
            }
        } else if (isSuperStockistRole) {
            if (superStockistId != null) {
                SuperStockist superStockist = superStockistRepository.findById(superStockistId)
                        .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));
                user.setSuperStockist(superStockist);
            } else if (user.getSuperStockist() == null) {
                user.setSuperStockist(findOrCreateSuperStockistFor(userAsRequest(user)));
            }
        }

        user.setApprovalStatus(com.spartan.dms.enums.ApprovalStatus.APPROVED);
        user.setActive(true);

        user = userRepository.save(user);

        auditLogService.log("APPROVE", "USER", user.getId(), "Approved account " + user.getUsername());

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("User Approved Successfully")
                .data(userMapper.toResponse(user))
                .build();
    }

    // Rejects a pending distributor registration. Rejected accounts stay
    // inactive and AuthService.login gives them a distinct "rejected"
    // message rather than the generic "awaiting approval" one.
    public ApiResponse<UserResponse> rejectUser(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setApprovalStatus(com.spartan.dms.enums.ApprovalStatus.REJECTED);
        user.setActive(false);

        user = userRepository.save(user);

        auditLogService.log("REJECT", "USER", user.getId(), "Rejected distributor account " + user.getUsername());

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("User Rejected")
                .data(userMapper.toResponse(user))
                .build();
    }

    // Self-service settings (Page 12): identity comes from SecurityUtils'
    // authenticated context, never a client-supplied id — so this can only
    // ever touch the caller's own row, whatever their role.
    public ApiResponse<UserResponse> updateOwnSettings(com.spartan.dms.dto.SettingsRequest request) {

        User user = securityUtils.getCurrentUser();

        if (request.getThemePreference() != null) user.setThemePreference(request.getThemePreference());
        if (request.getFontSizePreference() != null) user.setFontSizePreference(request.getFontSizePreference());
        if (request.getProfileImage() != null) user.setProfileImage(request.getProfileImage());

        user = userRepository.save(user);

        auditLogService.log("UPDATE", "SETTINGS", user.getId(), "Updated own settings");

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("Settings Updated Successfully")
                .data(userMapper.toResponse(user))
                .build();
    }

    public ApiResponse<String> resetPassword(Long id, String password) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        requirePassword(password);
        user.setPassword(passwordEncoder.encode(password));

        userRepository.save(user);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Password Reset Successfully")
                .data("Success")
                .build();
    }

    public ApiResponse<UserResponse> getProfile() {

        User user = securityUtils.getCurrentUser();

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("Profile")
                .data(userMapper.toResponse(user))
                .build();
    }

    public ApiResponse<UserResponse> updateProfile(UserRequest request) {

        User user = securityUtils.getCurrentUser();

        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setMobileNumber(request.getMobileNumber());

        // Changing your own password through self-service requires proving
        // you already know the current one first. Without this, anyone
        // holding a still-valid JWT (stolen via XSS, a shared/unlocked
        // device, browser history, etc.) could silently set a new password
        // and lock the real account owner out permanently -- the token
        // alone was previously sufficient to take over the account.
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
                throw new BadRequestException("Current password is required to set a new password");
            }
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new BadRequestException("Current password is incorrect");
            }
            validatePasswordStrength(request.getPassword());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        user = userRepository.save(user);

        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("Profile Updated Successfully")
                .data(userMapper.toResponse(user))
                .build();
    }

    // ---- password validation helpers ----
    // No @Valid on the controllers that reach these (see createUser's
    // comment for why), so this is the actual enforcement point.

    // BUG-L14: raised from 6 to 8, and now also requires at least one
    // letter and one digit (no special-character requirement -- keeping
    // this reasonable rather than enterprise-paranoid).
    private static final int MIN_PASSWORD_LENGTH = 8;

    private void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new BadRequestException("Password is required");
        }
        validatePasswordStrength(password);
    }

    private void validatePasswordStrength(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH
                || !password.chars().anyMatch(Character::isLetter)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw new BadRequestException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH
                            + " characters and contain at least one letter and one digit");
        }
    }
}