package com.fpt.autodata.service.automation;

import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.GeneratorException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Facade over the existing FTMS page objects.
 *
 * <p>Generators get a typed entry point here instead of dealing with the
 * driver lifecycle directly: every action acquires a driver, runs, and
 * releases it deterministically. On exception we optionally capture a PNG
 * screenshot of the current page (see {@code autodata.webdriver.screenshot-on-error}).</p>
 */
@Component
public class FtmsAutomation {

    private static final Logger log = LoggerFactory.getLogger(FtmsAutomation.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final WebDriverFactory drivers;
    private final AutodataProperties.WebDriver config;

    public FtmsAutomation(WebDriverFactory drivers, AutodataProperties props) {
        this.drivers = drivers;
        this.config = props.getWebdriver();
    }

    /** Run a void action against a fresh driver. The driver is always closed. */
    public void withDriver(Consumer<WebDriver> action) {
        WebDriver driver = drivers.create();
        try {
            action.accept(driver);
        } catch (RuntimeException e) {
            String shotPath = captureScreenshot(driver);
            String msg = "Automation step failed: " + e.getMessage()
                       + (shotPath != null ? " · screenshot=" + shotPath : "");
            throw new GeneratorException(msg, e);
        } finally {
            quietlyQuit(driver);
        }
    }

    /** Run a value-producing action against a fresh driver. */
    public <T> T computeWithDriver(Function<WebDriver, T> action) {
        WebDriver driver = drivers.create();
        try {
            return action.apply(driver);
        } catch (RuntimeException e) {
            String shotPath = captureScreenshot(driver);
            String msg = "Automation step failed: " + e.getMessage()
                       + (shotPath != null ? " · screenshot=" + shotPath : "");
            throw new GeneratorException(msg, e);
        } finally {
            quietlyQuit(driver);
        }
    }

    /**
     * Save a PNG screenshot of the current driver state to the configured
     * directory. Returns the absolute path, or {@code null} on best-effort
     * failure / when the feature is disabled.
     */
    private String captureScreenshot(WebDriver driver) {
        if (!config.isScreenshotOnError()) return null;
        if (!(driver instanceof TakesScreenshot)) return null;
        try {
            byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            Path dir = Paths.get(config.getScreenshotDir());
            Files.createDirectories(dir);
            String file = "error_" + LocalDateTime.now().format(TS) + ".png";
            Path target = dir.resolve(file);
            Files.write(target, bytes);
            String abs = target.toAbsolutePath().toString();
            log.warn("captured error screenshot: {}", abs);
            return abs;
        } catch (IOException | RuntimeException ioe) {
            log.warn("failed to capture screenshot: {}", ioe.getMessage());
            return null;
        }
    }

    private void quietlyQuit(WebDriver driver) {
        try { driver.quit(); } catch (Exception ignored) { /* best-effort */ }
    }
}
