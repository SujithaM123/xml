package com.project.toolkit.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Allows the Vite dev server (a different origin/port than this API) to call it during local
 * development -- this is a local tool, not a multi-tenant public service, so an open CORS policy
 * on /api/** is the right tradeoff here. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("*").allowedHeaders("*");
    }
}
