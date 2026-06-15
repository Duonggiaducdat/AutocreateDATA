package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
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
        click(btnOptionOtp);
        sendKeys(By.id("username"),username);
        click(btnNext);
        sendKeys(inputPass,password);
        click(btnLogin);
        waitForPageLoaded();
        sleep(3);
        hideChatbot();
    }
}
