package com.fpt.autodata.service.automation;

import com.fpt.autodata.config.AutodataProperties;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds short-lived WebDriver instances. Each generator request gets its own
 * driver — simple, isolated, and easy to reason about.
 *
 * <p>For session continuity across multiple HTTP calls (login + downstream
 * actions), introduce a {@code SessionManager} keyed by a {@code sessionId}
 * request param and store drivers there.</p>
 */
@Component
public class WebDriverFactory {

    private final AutodataProperties.WebDriver config;

    public WebDriverFactory(AutodataProperties props) {
        this.config = props.getWebdriver();
        WebDriverManager.chromedriver().setup();
    }

    public WebDriver create() {
        ChromeOptions options = new ChromeOptions();
        if (config.isHeadless()) {
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");  // headless cần explicit size
        } else {
            options.addArguments("--start-maximized");        // visible mode mới maximize được
        }
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");

        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts()
                .implicitlyWait(Duration.ofSeconds(config.getImplicitWaitSeconds()))
                .pageLoadTimeout(Duration.ofSeconds(config.getPageLoadTimeoutSeconds()));
        return driver;
    }
}
