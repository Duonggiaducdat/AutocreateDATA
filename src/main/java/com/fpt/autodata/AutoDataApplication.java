package com.fpt.autodata;

import com.fpt.autodata.config.AutodataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Auto-Create DATA backend.
 *
 * <p>The application is intentionally thin — generators self-register as Spring
 * beans and the registry composes them into a strategy lookup. Add a new
 * {@code @Component} that implements {@link com.fpt.autodata.generator.Generator}
 * and it shows up in the catalog automatically.</p>
 */
@EnableConfigurationProperties(AutodataProperties.class)
@SpringBootApplication
public class AutoDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoDataApplication.class, args);
    }
}
