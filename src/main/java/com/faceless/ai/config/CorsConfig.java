package com.faceless.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
@Slf4j
public class CorsConfig implements WebMvcConfigurer {

    /**
     * Comma-separated list of CORS origin <em>patterns</em>. Supports the
     * wildcard {@code *} so {@code https://*.ngrok-free.app} matches every
     * rotating ngrok tunnel without re-editing this list.
     *
     * <p>We use {@code allowedOriginPatterns} (not {@code allowedOrigins})
     * because {@code allowCredentials=true} forbids the bare {@code *}
     * wildcard per the CORS spec; pattern-matching echoes the request origin
     * back so credentials remain valid.
     */
    @Value("${chronicleai.cors.allowed-origins:" +
            "http://localhost:5173," +
            "http://localhost:4173," +
            "https://*.ngrok-free.app," +
            "https://*.ngrok-free.dev," +
            "https://*.ngrok.app," +
            "https://*.ngrok.dev," +
            "https://*.ngrok.io" +
            "}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        // Log the active patterns at startup so a missing
        // CHRONICLEAI_CORS_ALLOWED_ORIGINS env var in prod is visible in the
        // logs immediately, instead of surfacing as a confusing "blocked by
        // CORS" error in the browser console after the first API call.
        log.info("CORS allowed origin patterns: {}", String.join(", ", patterns));

        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-USER")
                .allowCredentials(true)
                .maxAge(3600);
    }
}