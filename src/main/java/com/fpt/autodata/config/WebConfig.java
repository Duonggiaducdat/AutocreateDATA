package com.fpt.autodata.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS configuration for the frontend SPA.
 * <p>Origins are sourced from {@code application.yml} so non-prod and prod
 * environments stay declarative.</p>
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
}