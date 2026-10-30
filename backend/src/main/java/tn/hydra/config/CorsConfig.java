package tn.hydra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS configuration — allows the Vercel frontend to call the Render backend.
 * Without this, the browser blocks cross-origin requests (404/403).
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // Allow all origins for now (can restrict to Vercel URL later)
        config.addAllowedOriginPattern("*");

        // Allow all headers (including X-XSRF-TOKEN for CSRF)
        config.addAllowedHeader("*");

        // Allow all methods
        config.addAllowedMethod("*");

        // Allow credentials (cookies — needed for session + CSRF)
        config.setAllowCredentials(true);

        // Expose the XSRF-TOKEN cookie header
        config.addExposedHeader("XSRF-TOKEN");

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
