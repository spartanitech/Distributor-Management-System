package com.spartan.dms.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    // BUG-C3 fix: no hardcoded fallback secret. A hardcoded default compiled
    // into the source would let anyone who reads this code forge tokens for
    // any user (including admins) against a deployment that forgot to set
    // JWT_SECRET. Instead we require JWT_SECRET to be set (see
    // application-local.properties.example for the local-dev signpost) and
    // fail fast at startup if it's missing or too weak.
    @org.springframework.beans.factory.annotation.Value("${JWT_SECRET:}")
    private String secret;

    private SecretKey key;

    @jakarta.annotation.PostConstruct
    private void init() {

        if (secret == null || secret.isBlank() || secret.trim().length() < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET environment variable must be set to a strong random value "
                            + "(32+ characters) -- refusing to start with no/weak secret.");
        }

        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String username) {

        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                // signWith(Key, SignatureAlgorithm) is deprecated in jjwt 0.12.x —
                // signWith(Key) alone infers the algorithm from the key type
                // (this key is HMAC-SHA256 from Keys.hmacShaKeyFor, so it
                // resolves to the same HS256 as before).
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) {

        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.getSubject();
    }

    // BUG-M3: used by JwtAuthenticationFilter to reject tokens issued before
    // a user's most recent logout (see User.tokensValidSince), following the
    // same claim-extraction pattern as extractUsername above.
    public java.time.LocalDateTime extractIssuedAt(String token) {

        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Date issuedAt = claims.getIssuedAt();

        return issuedAt == null
                ? null
                : java.time.LocalDateTime.ofInstant(issuedAt.toInstant(), java.time.ZoneId.systemDefault());
    }

    public boolean validateToken(String token) {

        try {

            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);

            return true;

        } catch (Exception e) {

            return false;
        }
    }
}