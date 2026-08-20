package com.spartan.dms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();

        // This API never uses cookies for anything — auth is a JWT the
        // frontend attaches itself via the Authorization header (see
        // apiRequest() in script.js), and nothing here ever sets a
        // Set-Cookie response header. allowCredentials(true) was
        // previously combined with a wildcard origin, which is a real
        // hole with zero corresponding benefit: it told browsers this API
        // accepts cookie-carrying cross-origin requests from ANY site, so
        // if a cookie-based feature (or a proxy/load balancer that sets
        // one) were ever added later, every other website on the internet
        // would already be pre-authorized to have it sent along
        // automatically. allowCredentials(false) + a literal wildcard
        // origin gives the exact same open-to-any-frontend behavior this
        // app actually needs, without that latent exposure.
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // The frontend reads the Authorization header back on some flows (and
        // browsers hide custom response headers by default), so expose it.
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}