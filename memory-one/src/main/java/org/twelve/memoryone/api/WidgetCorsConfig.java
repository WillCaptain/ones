package org.twelve.memoryone.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * AIPP widget assets must be loadable cross-origin by the Host shell
 * (Plan D: host does {@code dynamic import('http://aipp:port/widgets/...js')}).
 *
 * <p>Only {@code /widgets/**} is exposed cross-origin. Tool / skill APIs stay
 * same-origin (called from host server-side, not directly from browser).
 */
@Configuration
public class WidgetCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/widgets/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .maxAge(3600);
    }
}
