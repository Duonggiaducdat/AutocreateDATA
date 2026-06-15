package com.fpt.autodata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Type-safe properties for the Selenium driver lifecycle.
 * Bound to the {@code autodata.webdriver} prefix in {@code application.yml}.
 */
@Configuration
@ConfigurationProperties(prefix = "autodata.webdriver")
public class WebDriverConfig {

    private boolean headless = true;
    private int implicitWaitSeconds = 5;
    private int pageLoadTimeoutSeconds = 60;
    private String baseUrl;

    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean headless) { this.headless = headless; }

    public int getImplicitWaitSeconds() { return implicitWaitSeconds; }
    public void setImplicitWaitSeconds(int implicitWaitSeconds) {
        this.implicitWaitSeconds = implicitWaitSeconds;
    }

    public int getPageLoadTimeoutSeconds() { return pageLoadTimeoutSeconds; }
    public void setPageLoadTimeoutSeconds(int pageLoadTimeoutSeconds) {
        this.pageLoadTimeoutSeconds = pageLoadTimeoutSeconds;
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
