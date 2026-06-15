package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class RequestCM extends BasePage {
    Actions actions;
    WebDriverWait wait;
    public RequestCM(WebDriver driver) {
        super(driver);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.actions = new Actions(driver);
    }
    private By detail(String index){
        return By.xpath(String.format("//a[contains(normalize-space(),'%s')]",index));
    }
    private By btnCreate = By.xpath("//span[text()=' Tạo hợp đồng & PL ']");
    private By btnAgree = By.xpath("//button[text()='Đồng ý']");
    private By btnSend = By.xpath("//span[text()=' Gửi yêu cầu triển khai ']");
    private By btnConfirm = By.xpath("//button[text()='Xác nhận']");
    public void request(String maYC){
        driver.get("https://ftms-stag.fpt.net/cm/v2/registration");
        waitForPageLoaded();
        sleep(5);
        click(detail(maYC));
        waitForPageLoaded();
        click(btnCreate);
        click(btnAgree);
        waitForPageLoaded();
        sleep(5);
        click(btnSend);
        click(btnConfirm);
        sleep(10);
    }
}
