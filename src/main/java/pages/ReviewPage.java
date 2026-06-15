package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import utils.DateUtil;

import java.time.Duration;

public class ReviewPage extends BasePage {
    Actions actions;
    WebDriverWait wait;
    public ReviewPage(WebDriver driver) {
        super(driver);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.actions = new Actions(driver);
    }
    private By btnReview = By.id("reviewButton");
    private By inputNote = By.id("noteModalInput");
    private By btnConfirm  = By.id("submitEvaluation");
    private By label = By.xpath("//span[text()='Phê duyệt']");

    public void review(){
        waitForPageLoaded();
        click(btnReview);
        sendKeys(inputNote,"Auto ghi chu");
        click(btnConfirm);
        waitForVisibility(label);
    }


}
