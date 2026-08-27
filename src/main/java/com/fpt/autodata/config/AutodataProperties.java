package com.fpt.autodata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Application configuration bound to the {@code autodata.*} branch of
 * {@code application.yml}. Registered as a bean by
 * {@code @EnableConfigurationProperties(AutodataProperties.class)} in
 * {@link com.fpt.autodata.AutoDataApplication}.
 *
 * <p><b>Important:</b> do <em>not</em> add {@code @Component} or
 * {@code @Configuration} here — that would cause a duplicate bean registration
 * because {@code @EnableConfigurationProperties} already registers it.</p>
 */
@ConfigurationProperties(prefix = "autodata")
public class AutodataProperties {

    private Cors cors = new Cors();
    private WebDriver webdriver = new WebDriver();
    private Api api = new Api();

    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }

    public WebDriver getWebdriver() { return webdriver; }
    public void setWebdriver(WebDriver webdriver) { this.webdriver = webdriver; }

    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }

    /* ----------------------------------------------------------------- */

    public static class Cors {
        /** Initialised to an empty list so {@code getAllowedOrigins()} is never null. */
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    /* ----------------------------------------------------------------- */

    /** Bound to {@code autodata.webdriver.*} in application.yml. */
    public static class WebDriver {
        private boolean headless = true;
        private int implicitWaitSeconds = 5;
        private int pageLoadTimeoutSeconds = 60;
        private String baseUrl;

        /** Take a screenshot when an automation step throws. */
        private boolean screenshotOnError = true;
        /** Directory where error screenshots are written. */
        private String screenshotDir = "screenshots";

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

        public boolean isScreenshotOnError() { return screenshotOnError; }
        public void setScreenshotOnError(boolean v) { this.screenshotOnError = v; }

        public String getScreenshotDir() { return screenshotDir; }
        public void setScreenshotDir(String v) { this.screenshotDir = v; }
    }

    /* ----------------------------------------------------------------- */

    /**
     * Bound to {@code autodata.api.*} — direct REST calls to the BMS backend,
     * bypassing Selenium entirely. Used by API-based generators (e.g.
     * {@code opportunity.api}) which are far less flaky than the WebDriver path.
     */
    public static class Api {
        /** Base URL for direct BMS REST calls (no Selenium involved). */
        private String baseUrl = "https://ftisapi-stag.fpt.net/bms-service/api";
        /** Request timeout in seconds for direct API calls. */
        private int timeoutSeconds = 15;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    }
}