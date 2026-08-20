package com.spartan.dms.security;

import com.spartan.dms.constants.SecurityConstants;
import com.spartan.dms.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    // BUG-M3: needed to read the actual User entity's tokensValidSince --
    // the UserDetails CustomUserDetailsService returns is Spring Security's
    // plain built-in User and doesn't carry that field.
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(SecurityConstants.HEADER);

        if (authHeader == null ||
                !authHeader.startsWith(SecurityConstants.TOKEN_PREFIX)) {

            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(
                SecurityConstants.TOKEN_PREFIX.length());

        try {
            if (jwtUtil.validateToken(token)) {

                String username = jwtUtil.extractUsername(token);

                if (username != null &&
                        SecurityContextHolder.getContext().getAuthentication() == null) {

                    UserDetails userDetails =
                            customUserDetailsService.loadUserByUsername(username);

                    // A JWT stays cryptographically valid for its full 24h
                    // lifetime regardless of what happens to the account
                    // after it was issued -- this check is what actually
                    // makes Admin's "deactivate user" / reject-account
                    // actions (UserService.updateUserStatus/rejectUser,
                    // both of which set User.active=false) take effect
                    // immediately instead of up to 24h later. Without it, a
                    // deactivated or rejected account keeps full API access
                    // on any token it obtained before that point.
                    // BUG-M3: reject tokens issued before the user's most
                    // recent logout. tokensValidSince is null for anyone who
                    // has never used the new logout flow, in which case this
                    // never rejects anything (unchanged behavior).
                    LocalDateTime tokensValidSince = userRepository.findByUsername(username)
                            .map(com.spartan.dms.entity.User::getTokensValidSince)
                            .orElse(null);
                    LocalDateTime issuedAt = jwtUtil.extractIssuedAt(token);

                    if (!userDetails.isEnabled()) {
                        log.debug("JWT authentication rejected: account '{}' is deactivated", username);
                    } else if (tokensValidSince != null && (issuedAt == null || issuedAt.isBefore(tokensValidSince))) {
                        log.debug("JWT authentication rejected: token for '{}' was issued before the account's last logout", username);
                    } else {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails,
                                        null,
                                        userDetails.getAuthorities()
                                );

                        authentication.setDetails(
                                new WebAuthenticationDetailsSource()
                                        .buildDetails(request)
                        );

                        SecurityContextHolder.getContext()
                                .setAuthentication(authentication);
                    }
                }
            }
        } catch (Exception ex) {
            // Expired / malformed / signature-mismatched token, or a username
            // that no longer exists. Instead of letting this bubble up as an
            // uncaught 500, leave the SecurityContext unauthenticated so the
            // request falls through to Spring Security's normal 403 handling
            // for protected endpoints — which the frontend already understands.
            log.debug("JWT authentication failed: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
