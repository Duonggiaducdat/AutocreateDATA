package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class LogoutPage {
    WebDriver driver;
    Actions actions;
    WebDriverWait wait;
    public LogoutPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.actions = new Actions(driver);
    }

    private By labelNavbarUser = By.id("navbarDropdownUser");
    private By btnLogout = By.xpath("//a[text()='Đăng xuất']");
    private By btnLogin = By.xpath("//a[text()=' đăng nhập ']");
    private By btnChooseAuthen = By.xpath("//p[text()='Chọn phương thức xác thực khác.']");
    public void Logout(){
        wait.until(ExpectedConditions.elementToBeClickable(labelNavbarUser)).click();
        wait.until(ExpectedConditions.elementToBeClickable(btnLogout)).click();
        wait.until(ExpectedConditions.elementToBeClickable(btnLogin)).click();
        wait.until(ExpectedConditions.elementToBeClickable(btnChooseAuthen)).click();
    }
}
