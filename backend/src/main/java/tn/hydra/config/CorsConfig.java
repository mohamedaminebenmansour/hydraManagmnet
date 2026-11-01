package tn.hydra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS — only the origins listed in HYDRA_ALLOWED_ORIGINS (comma-separated; default
 * http://localhost:4200), exact match, with credentials (session + CSRF cookies).
 * Never "*" with credentials. Used by Spring Security (SecurityConfig .cors()).
 *
 * <p>On Render, set HYDRA_ALLOWED_ORIGINS to the Vercel URL: the browser sends that
 * Origin header even through the Vercel /api rewrite, and a request from an origin
 * not in the list is refused.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${HYDRA_ALLOWED_ORIGINS:http://localhost:4200}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty() && !o.equals("*"))
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.addExposedHeader("XSRF-TOKEN");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
