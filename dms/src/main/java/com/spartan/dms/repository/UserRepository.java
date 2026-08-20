package com.spartan.dms.repository;

import com.spartan.dms.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // JOIN FETCH the role eagerly: User.role is @ManyToOne(LAZY), and both
    // CustomUserDetailsService (building "ROLE_" + role.getRoleName()) and
    // AuthService.login() access it right after this call returns. Without
    // eager fetching here, that access happens after the repository's own
    // transaction/session has already closed, throwing a
    // LazyInitializationException — which JwtAuthenticationFilter quietly
    // swallows and turns into a 403 on every protected endpoint.
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.username = :username")
    Optional<User> findByUsername(@Param("username") String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByMobileNumber(String mobileNumber);

    // BUG-L13 fix: resetToken is now stored bcrypt-hashed (never plaintext),
    // so it can no longer be looked up by direct equality against the raw
    // token from the reset link. Instead AuthService.resetPassword() fetches
    // this small set of still-active candidates (there are only ever a
    // handful of outstanding, non-expired reset tokens at once) and matches
    // the raw token against each stored hash via PasswordEncoder.matches().
    List<User> findByResetTokenIsNotNullAndResetTokenExpiryAfter(LocalDateTime now);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByMobileNumber(String mobileNumber);

    boolean existsByUsernameAndIdNot(String username, Long id);

    boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByMobileNumberAndIdNot(String mobileNumber, Long id);

    /* ---------- Delete-guard checks ---------- */

    boolean existsByDistributorId(Long distributorId);

    boolean existsBySuperStockistId(Long superStockistId);

    // Used to refuse deleting/deactivating/demoting the last remaining
    // active admin account -- once that count hits zero there is no way
    // back in (self-registration never grants ADMIN, see AuthService).
    long countByRole_RoleNameAndActiveTrue(String roleName);
}