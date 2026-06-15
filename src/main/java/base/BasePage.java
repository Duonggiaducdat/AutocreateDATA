package base;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public abstract class BasePage {

    protected WebDriver driver;
    protected WebDriverWait wait;
    protected final Logger log = LogManager.getLogger(getClass());

    protected static final int DEFAULT_TIMEOUT = 15;
    protected static final String TEXT_CONTENT = "textContent";
    protected static final String ARIA_CHECKED = "aria-checked";

    public BasePage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(DEFAULT_TIMEOUT));
    }

    /* ========================= FIND ELEMENT ========================= */

    protected WebElement getElement(By locator) {
        return wait.until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    protected List<WebElement> getElements(By locator) {
        return wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(locator));
    }

    /* ========================= WAIT ========================= */

    public void waitForVisibility(By locator) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public void waitForClickable(By locator) {
        wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    public void waitForInvisibility(By locator) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(locator));
    }

    public void waitForAttributeToBe(By locator, String attribute, String value) {
        wait.until(ExpectedConditions.attributeToBe(locator, attribute, value));
    }

    public void waitForUrlContains(String url) {
        wait.until(ExpectedConditions.urlContains(url));
    }

    public void waitForPageLoaded() {
        wait.until(driver ->
                ((JavascriptExecutor) driver)
                        .executeScript("return document.readyState")
                        .equals("complete"));
    }

    /* ========================= ACTION ========================= */

    public void click(By locator) {

        log.warn("START CLICK {}", locator);

        for (int i = 1; i <= 3; i++) {
            try {

                waitForClickable(locator);

                WebElement el = getElement(locator);

                log.warn("NORMAL CLICK {}", locator);

                el.click();

                log.warn("NORMAL CLICK DONE {}", locator);

                return;

            } catch (ElementClickInterceptedException e) {

                log.warn("JS CLICK {}", locator);

                scrollIntoView(locator);

                clickByJS(locator);

                return;
            }
        }
    }

    public void clickByJS(By locator) {
        WebElement element = getElement(locator);
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
    }

    /**
     * Click có retry — thử lại tối đa maxRetry lần nếu element chưa clickable
     * Dùng cho Kendo dropdown option hay bị flaky
     */
    public void clickWithRetry(By locator, int maxRetry) {
        for (int i = 1; i <= maxRetry; i++) {
            try {
                log.debug("ClickWithRetry [{}/{}]: {}", i, maxRetry, locator);
                waitForClickable(locator);
                getElement(locator).click();
                return;
            } catch (Exception e) {
                log.warn("Retry {}/{} failed: {}", i, maxRetry, e.getMessage());
                if (i == maxRetry) throw e;
                sleep(1);
                try { scrollIntoView(locator); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Click bằng Actions — dùng cho Kendo Grid cell có background đỏ/validation
     * mà click thường bị intercept hoặc không kích hoạt inline editing
     */
    public void clickByActions(By locator) {
        log.debug("ClickByActions: {}", locator);
        WebElement element = getElement(locator);
        new Actions(driver).moveToElement(element).click().perform();
    }

    public void sendKeys(By locator, String text) {
        log.debug("SendKeys: {} = \"{}\"", locator, text);
        waitForVisibility(locator);
        WebElement element = getElement(locator);
        element.clear();
        element.sendKeys(text);
    }
    public void clickAndSendKeys(By locator, String text) {
        waitForVisibility(locator);
        WebElement element = getElement(locator);
        element.clear();
        click(locator);
        element.sendKeys(text);
    }

    public void hover(By locator) {
        new Actions(driver)
                .moveToElement(getElement(locator))
                .perform();
    }

    public void rightClick(By locator) {
        new Actions(driver)
                .contextClick(getElement(locator))
                .perform();
    }

    /* ========================= GET ========================= */

    public String getText(By locator) {
        waitForVisibility(locator);
        return getElement(locator).getText();
    }
    public void uploadFile(String filePath, By locator) {
        WebElement input = wait.until(
                ExpectedConditions.presenceOfElementLocated(locator));

        input.sendKeys(filePath);
    }

    public String getAttribute(By locator, String attribute) {
        WebElement element = getElement(locator);
        if (TEXT_CONTENT.equalsIgnoreCase(attribute)) {
            return (String) ((JavascriptExecutor) driver)
                    .executeScript("return arguments[0].textContent;", element);
        }
        return element.getAttribute(attribute);
    }

    public boolean isDisplayed(By locator) {
        try {
            log.info("Check displayed: {}", locator);

            waitForVisibility(locator);

            boolean displayed = getElement(locator).isDisplayed();

            log.info("Displayed = {} : {}", displayed, locator);

            return displayed;

        } catch (TimeoutException e) {

            log.warn("Element NOT displayed (timeout): {}", locator);

            return false;

        } catch (Exception e) {

            log.error("Error checking displayed: {}", locator, e);

            return false;
        }
    }

    /* ========================= SCROLL ========================= */

    public void scrollIntoView(By locator) {
        WebElement element = getElement(locator);
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block:'center'});", element);
    }

    public void hideChatbot() {
        ((JavascriptExecutor) driver).executeScript(
                "let btn = document.querySelector('.chatbot-btn');" +
                        "if(btn) btn.style.display='none';"
        );
    }
    public void scrollToTop() {
        ((JavascriptExecutor) driver)
                .executeScript("window.scrollTo(0,0)");
    }

    public void scrollToBottom() {
        ((JavascriptExecutor) driver)
                .executeScript("window.scrollTo(0,document.body.scrollHeight)");
    }

    /* ========================= NAVIGATION ========================= */

    public void openUrl(String url) {
        log.info("Navigate: {}", url);
        driver.get(url);
        waitForPageLoaded();
    }

    public void refresh() {
        driver.navigate().refresh();
        waitForPageLoaded();
    }


    public void back() {
        driver.navigate().back();
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public String getTitle() {
        return driver.getTitle();
    }

    /* ========================= TAB ========================= */

    public String openNewTab(String url) {
        String original = driver.getWindowHandle();
        ((JavascriptExecutor) driver).executeScript("window.open()");
        List<String> tabs = new ArrayList<>(driver.getWindowHandles());
        driver.switchTo().window(tabs.get(1));
        openUrl(url);
        return original;
    }

    public void switchToTab(String tabId) {
        driver.switchTo().window(tabId);
    }

    /* ========================= SWITCH ========================= */

    public void turnOnSwitch(By locator) {
        if (!"true".equals(getAttribute(locator, ARIA_CHECKED))) {
            click(locator);
        }
    }

    public void turnOffSwitch(By locator) {
        if ("true".equals(getAttribute(locator, ARIA_CHECKED))) {
            click(locator);
        }
    }

    /* ========================= UTIL ========================= */

    public void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    public void closeUnexpectedWindows() {
        String mainWindow = driver.getWindowHandle();

        for (String window : driver.getWindowHandles()) {
            if (!window.equals(mainWindow)) {
                driver.switchTo().window(window);
                driver.close(); // đóng cửa sổ chatbot / popup
            }
        }

        driver.switchTo().window(mainWindow); // quay lại window chính
    }
    public void clickBody(){
        driver.findElement(By.tagName("body")).click();
    }

    public String getNumberFromText(String text) {
        return text.replaceAll("\\D+", "");
    }
}