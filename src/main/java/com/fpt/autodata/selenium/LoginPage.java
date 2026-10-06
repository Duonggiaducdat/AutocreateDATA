package com.fpt.autodata.selenium;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class LoginPage extends BasePage {
    Actions actions;
    WebDriverWait wait;
    public LoginPage(WebDriver driver) {
        super(driver);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.actions = new Actions(driver);
    }

    private By btnOptionOtp = By.xpath("//p[text()='Sử dụng mật khẩu một lần (OTP)']");
    private By btnNext = By.xpath("//button[text()='TIẾP THEO']");
    private By inputPass = By.xpath("//input[@type=\"password\"]");
    private By btnLogin = By.xpath("//button[text()='ĐĂNG NHẬP']");

    public void login(String username, String password) {
        openUrl("https://ftms-stag.fpt.net/bms/OppsMagt/Create");
        doOtpLoginIfNeeded(username, password);
        hideChatbot();
    }

    /** Login cho một target URL cụ thể (vd: new-CM) — navigate thẳng đến URL.
     *  Nếu CAS redirect đến login page thì thực hiện OTP flow.
     *  Nếu đã login sẵn (CAS auto-redirect về target) thì bỏ qua bước login.
     *  Luôn chọn "Sử dụng mật khẩu một lần (OTP)", không dùng option FPT. */
    public void loginToUrl(String targetUrl, String username, String password) {
        openUrl(targetUrl);
        doOtpLoginIfNeeded(username, password);
        // else: TGT còn hạn, CAS đã tự redirect về target → không cần login lại
    }

    /**
     * Kiểm tra login page có hiện không → nếu có thì thực hiện OTP flow.
     * Sau khi click "TIẾP THEO", chờ tối đa 5s để phát hiện lỗi sớm thay vì
     * đợi 15s timeout của sendKeys cho ô password không bao giờ xuất hiện
     * (xảy ra khi account không tồn tại trong hệ thống FPT Telecom).
     */
    private void doOtpLoginIfNeeded(String username, String password) {
        boolean loginPageShown;
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.presenceOfElementLocated(btnOptionOtp));
            loginPageShown = true;
        } catch (org.openqa.selenium.TimeoutException e) {
            loginPageShown = false;
        }
        if (!loginPageShown) return;

        click(btnOptionOtp);   // luôn chọn OTP, không dùng FPT SSO
        sendKeys(By.id("username"), username);
        click(btnNext);

        // Chờ hoặc ô password xuất hiện, hoặc thông báo lỗi — tối đa 5s.
        // Nếu server báo "Tài khoản không chính xác" thì throw ngay, tránh
        // chờ 15s timeout vô ích của sendKeys(inputPass, ...).
        By errLocator = By.xpath(
            "//*[contains(text(),'không chính xác') or contains(text(),'Vui lòng thử lại')]");
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5)).until(
                ExpectedConditions.or(
                    ExpectedConditions.visibilityOfElementLocated(inputPass),
                    ExpectedConditions.visibilityOfElementLocated(errLocator)
                )
            );
        } catch (org.openqa.selenium.TimeoutException ignored) {
            // Không phát hiện được gì trong 5s → để sendKeys tự timeout với message mặc định
        }
        try {
            org.openqa.selenium.WebElement errEl = driver.findElement(errLocator);
            if (errEl.isDisplayed()) {
                throw new RuntimeException(
                    "CAS/FPT Telecom login thất bại cho '" + username + "': "
                    + errEl.getText().trim()
                    + " — kiểm tra account có tồn tại và hỗ trợ OTP không.");
            }
        } catch (org.openqa.selenium.NoSuchElementException ignored) {
            // Không có lỗi → password field xuất hiện, tiếp tục bình thường
        }

        sendKeys(inputPass, password);
        click(btnLogin);
        waitForPageLoaded();
        sleep(3);
    }
}
