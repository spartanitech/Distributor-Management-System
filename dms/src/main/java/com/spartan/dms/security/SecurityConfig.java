package com.spartan.dms.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // Wire in the CorsConfigurationSource bean (CorsConfig.java) so that
                // preflight OPTIONS requests and the actual cross-origin responses
                // carry the correct Access-Control-* headers. Without this line the
                // @CrossOrigin annotations on controllers are never reached because
                // Spring Security rejects the request first.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Always allow CORS preflight requests through, regardless of path.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/login.html",
                                "/dashboard.html",
                                "/products.html",
                                "/categories.html",
                                "/shops.html",
                                "/distributors.html",
                                "/invoices.html",
                                "/payments.html",
                                "/reports.html",
                                "/settings.html",

                                // The DMS frontend keeps its assets flat at the
                                // static root (style.css, script.js) rather than
                                // under /css/** or /js/**, so they need their own
                                // entries here — otherwise they fall through to
                                // anyRequest().authenticated() and 403 before the
                                // user has ever logged in.
                                "/style.css",
                                "/script.js",
                                "/favicon.ico",

                                "/css/**",
                                "/js/**",
                                "/images/**",

                                // NOTE: the real auth endpoints are mapped under
                                // "/api/v1/auth/**" (see AuthController). The previous
                                // "/api/auth/**" pattern never matched, which meant
                                // /api/v1/auth/login itself required a JWT it could
                                // never obtain — the root cause of the cascading 403s.
                                "/api/v1/auth/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // Admin-only backend management. Fine-grained rules
                        // for individual endpoints inside these controllers
                        // (e.g. which /users sub-paths, GET vs write) are
                        // enforced with @PreAuthorize at the method level;
                        // this is the coarse safety net.
                        .requestMatchers("/api/v1/users/**").hasAnyRole("ADMIN", "DISTRIBUTOR", "SUPER_STOCKIST")

                        // Everything else just needs a valid, authenticated
                        // session — distributor-vs-admin scoping for
                        // invoices/payments/reports/shops/product-requests
                        // is enforced in the service layer via SecurityUtils
                        // (a DISTRIBUTOR login only ever sees data tied to
                        // their own distributor_id) and via @PreAuthorize on
                        // admin-only write endpoints (e.g. distributor/
                        // product/category management, report generation).
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                // BUG-M2: a missing/invalid JWT now gets the app's standard
                // JSON error envelope (401) instead of a bare container 403.
                // AccessDeniedException (valid token, wrong role) is a
                // separate concern and is untouched -- it still goes through
                // GlobalExceptionHandler.handleAccessDenied() as before.
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(restAuthenticationEntryPoint));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {

        return configuration.getAuthenticationManager();
    }
}
