package com.fpt.autodata.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS configuration for the frontend SPA, plus SPA-style URL fallbacks for
 * the hand-rolled 2-page router in {@code app.js} ("/" home, "/ivoice" —
 * user request 2026-09-03: a real address-bar URL, not just a JS show/hide
 * with no URL change). Static {@code index.html} already contains both
 * pages (toggled via {@code hidden}); this just makes a direct hit or
 * reload of {@code /ivoice} serve the same shell instead of 404ing, since
 * Spring Boot's static-resource handler only knows about real files.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AutodataProperties props;

    public WebConfig(AutodataProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(props.getCors().getAllowedOrigins()
                        .toArray(new String[0]))
                .allowedMethods("*")
                .allowedHeaders("*");
    }

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        registry.addViewController("/ivoice").setViewName("forward:/index.html");
        registry.addViewController("/sm").setViewName("forward:/index.html");
        registry.addViewController("/mes").setViewName("forward:/index.html");
        registry.addViewController("/idc").setViewName("forward:/index.html");
        registry.addViewController("/ll").setViewName("forward:/index.html");
    }
}