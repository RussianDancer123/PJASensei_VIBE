package pl.edu.pjasensei.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Allowed origins are configuration because a real IDE plugin has a different origin. */
@Configuration(proxyBeanMethods = false)
class CrossOriginConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    CrossOriginConfig(@Value("${pjasensei.cors.allowed-origins:}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins);
    }
}
