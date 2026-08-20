package com.spartan.dms.constants;

public final class SecurityConstants {

    private SecurityConstants() {
    }

    // BUG-C3: removed the unused SECRET_KEY / JWT_EXPIRATION constants that
    // used to live here. They were dead code never referenced by JwtUtil
    // (the real secret/expiry live in JwtUtil, sourced from the required
    // JWT_SECRET env var) and could mislead a future developer into
    // thinking this was the live signing key.

    public static final String TOKEN_PREFIX = "Bearer ";

    public static final String HEADER = "Authorization";
}