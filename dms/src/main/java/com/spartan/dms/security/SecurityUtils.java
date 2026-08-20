package com.spartan.dms.security;

import com.spartan.dms.entity.User;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Central place every service uses to answer two questions:
 *   1. Who is the currently authenticated user?
 *   2. If they're a DISTRIBUTOR login, which distributor_id are they
 *      scoped to?
 *
 * Controllers/services should never read SecurityContextHolder directly —
 * route everything through here so the scoping logic lives in one place.
 */
@Component
@RequiredArgsConstructor
public class SecurityUtils {

    private final UserRepository userRepository;
    private final DistributorRepository distributorRepository;

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SUPER_STOCKIST = "SUPER_STOCKIST";
    public static final String ROLE_DISTRIBUTOR = "DISTRIBUTOR";

    /** Full User entity (with role + distributor eagerly loaded) for the caller. */
    public User getCurrentUser() {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserDetails)) {
            throw new ForbiddenException("No authenticated user in context");
        }

        String username = ((UserDetails) auth.getPrincipal()).getUsername();

        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ForbiddenException("Authenticated user no longer exists"));
    }

    public boolean isAdmin() {
        return hasRole(ROLE_ADMIN);
    }

    public void assertAdmin() {
        if (!isAdmin()) {
            throw new ForbiddenException("This action is restricted to Admin");
        }
    }

    public boolean isSuperStockist() {
        return hasRole(ROLE_SUPER_STOCKIST);
    }

    public boolean isDistributor() {
        return hasRole(ROLE_DISTRIBUTOR);
    }

    private boolean hasRole(String role) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null) {
            return false;
        }

        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (authority.getAuthority().equals("ROLE_" + role)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The distributor_id the current caller is restricted to, or null if
     * they're an ADMIN (unrestricted). Throws if a DISTRIBUTOR login has no
     * linked distributor record — that's a data-setup error, not something
     * that should silently leak every distributor's data.
     */
    public Long getScopedDistributorId() {

        if (isAdmin()) {
            return null;
        }

        if (isSuperStockist()) {
            throw new ForbiddenException(
                    "A Super Stockist login is scoped to many distributors, not one; "
                            + "use assertDistributorAccess(id) or getScopedSuperStockistId() instead");
        }

        User user = getCurrentUser();

        if (user.getDistributor() == null) {
            throw new ForbiddenException("This account is not linked to a distributor");
        }

        return user.getDistributor().getId();
    }

    /**
     * The super_stockist_id the current caller is restricted to, or null if
     * they're an ADMIN (unrestricted). Throws if a SUPER_STOCKIST login has
     * no linked super stockist record.
     */
    public Long getScopedSuperStockistId() {

        if (isAdmin()) {
            return null;
        }

        User user = getCurrentUser();

        if (user.getSuperStockist() == null) {
            throw new ForbiddenException("This account is not linked to a super stockist");
        }

        return user.getSuperStockist().getId();
    }

    /**
     * Throws ForbiddenException if the current caller can't touch the given
     * distributor's data:
     *   - ADMIN: always allowed.
     *   - SUPER_STOCKIST: allowed only if that distributor is assigned to them.
     *   - DISTRIBUTOR: allowed only if it's their own record.
     * Use this to guard single-record reads/writes (invoices, payments,
     * reports, stock, etc.) so a distributor can never see another
     * distributor's data and a super stockist can never see another super
     * stockist's distributors.
     */
    public void assertDistributorAccess(Long targetDistributorId) {

        if (isAdmin()) {
            return;
        }

        if (targetDistributorId == null) {
            throw new ForbiddenException("You do not have access to this distributor's data");
        }

        if (isSuperStockist()) {
            Long scopedSuperStockistId = getScopedSuperStockistId();
            boolean owned = distributorRepository.existsByIdAndSuperStockistId(
                    targetDistributorId, scopedSuperStockistId);
            if (!owned) {
                throw new ForbiddenException("You do not have access to this distributor's data");
            }
            return;
        }

        Long scopedId = getScopedDistributorId();

        if (!targetDistributorId.equals(scopedId)) {
            throw new ForbiddenException("You do not have access to this distributor's data");
        }
    }

    /**
     * Throws ForbiddenException if the current caller can't touch the given
     * super stockist's data (ADMIN always allowed; SUPER_STOCKIST only for
     * their own record; DISTRIBUTOR never).
     */
    public void assertSuperStockistAccess(Long targetSuperStockistId) {

        if (isAdmin()) {
            return;
        }

        if (!isSuperStockist() || targetSuperStockistId == null
                || !targetSuperStockistId.equals(getScopedSuperStockistId())) {
            throw new ForbiddenException("You do not have access to this super stockist's data");
        }
    }
}
